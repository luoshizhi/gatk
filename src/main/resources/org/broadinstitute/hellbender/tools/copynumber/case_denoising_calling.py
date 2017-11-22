import os

# set theano flags
os.environ["THEANO_FLAGS"] = "device=cpu,floatX=float64,optimizer=fast_run,compute_test_value=ignore"

import logging
import argparse
import gcnvkernel
import shutil
import json
from typing import Dict, Any

logger = logging.getLogger(__name__)

parser = argparse.ArgumentParser(description="gCNV case calling tool based on a previously trained model",
                                 formatter_class=gcnvkernel.cli_commons.GCNVHelpFormatter)

# logging args
gcnvkernel.cli_commons.add_logging_args_to_argparse(parser)

# add tool-specific args
group = parser.add_argument_group(title="Required arguments")

group.add_argument("--input_model_path",
                   type=str,
                   required=True,
                   default=argparse.SUPPRESS,
                   help="Path to denoising model parameters")

group.add_argument("--read_count_tsv_files",
                   type=str,
                   required=True,
                   nargs='+',  # one or more
                   default=argparse.SUPPRESS,
                   help="List of read count files in the cohort (in .tsv format; must include sample name header)")

group.add_argument("--ploidy_calls_path",
                   type=str,
                   required=True,
                   default=argparse.SUPPRESS,
                   help="The path to the results of ploidy determination tool")

group.add_argument("--output_calls_path",
                   type=str,
                   required=True,
                   default=argparse.SUPPRESS,
                   help="Output path to write CNV calls")

group.add_argument("--output_adamax_path",
                   type=str,
                   required=False,
                   default=argparse.SUPPRESS,
                   help="(advanced) Output path to write adamax moment estimates")

group.add_argument("--input_calls_path",
                   type=str,
                   required=False,
                   default=argparse.SUPPRESS,
                   help="Path to previously obtained calls to take as starting point")

group.add_argument("--input_adamax_path",
                   type=str,
                   required=False,
                   default=argparse.SUPPRESS,
                   help="(advanced) Path to previously obtained adamax moment estimates take as starting point")

# add denoising config args
# Note: we are hiding parameters that are either set by the model or are irrelevant to the case calling task
gcnvkernel.DenoisingModelConfig.expose_args(
    parser,
    hide={
        "--max_bias_factors",
        "--psi_j_scale",
        "--log_mean_bias_std",
        "--init_ard_rel_unexplained_variance",
        "--enable_bias_factors",
        "--enable_explicit_gc_bias_modeling",
        "--disable_bias_factors_in_flat_class",
        "--num_gc_bins",
        "--gc_curve_sd",
    })

# add calling config args
# Note: we are hiding parameters that are either set by the model or are irrelevant to the case calling task
gcnvkernel.CopyNumberCallingConfig.expose_args(
    parser,
    hide={
        '--p_flat',
        '--class_coherence_length',
        '--initialize_to_flat_class'
    })

# override some inference parameters
gcnvkernel.HybridInferenceParameters.expose_args(
    parser,
    hide={
        "--disable_sampler",
        "--disable_caller"
    })


def update_args_dict_from_exported_model(input_model_path: str,
                                         _args_dict: Dict[str, Any]):

    logging.info("Loading denoising model configuration from the provided model...")
    with open(os.path.join(input_model_path, "denoising_config.json"), 'r') as fp:
        imported_denoising_config_dict = json.load(fp)

    # boolean flags
    _args_dict['enable_bias_factors'] =\
        imported_denoising_config_dict['enable_bias_factors']
    _args_dict['enable_explicit_gc_bias_modeling'] =\
        imported_denoising_config_dict['enable_explicit_gc_bias_modeling']
    _args_dict['disable_bias_factors_in_flat_class'] =\
        imported_denoising_config_dict['disable_bias_factors_in_flat_class']

    # bias factor related
    _args_dict['max_bias_factors'] =\
        imported_denoising_config_dict['max_bias_factors']

    # gc-related
    _args_dict['num_gc_bins'] =\
        imported_denoising_config_dict['num_gc_bins']
    _args_dict['gc_curve_sd'] =\
        imported_denoising_config_dict['gc_curve_sd']

    logging.info("- bias factors enabled: "
                 + repr(_args_dict['enable_bias_factors']))
    logging.info("- explicit GC bias modeling enabled: "
                 + repr(_args_dict['enable_explicit_gc_bias_modeling']))
    logging.info("- bias factors in flat classes disabled: "
                 + repr(_args_dict['disable_bias_factors_in_flat_class']))

    if _args_dict['enable_bias_factors']:
        logging.info("- maximum number of bias factors: "
                     + repr(_args_dict['enable_bias_factors']))

    if _args_dict['enable_explicit_gc_bias_modeling']:
        logging.info("- number of GC curve knobs: "
                     + repr(_args_dict['num_gc_bins']))
        logging.info("- GC curve prior standard deviation: "
                     + repr(_args_dict['gc_curve_sd']))


if __name__ == "__main__":

    # parse arguments
    args = parser.parse_args()
    gcnvkernel.cli_commons.set_logging_config_from_args(args)

    # load modeling interval list from the model
    logging.info("Loading modeling interval list from the provided model...")
    modeling_interval_list = gcnvkernel.io_intervals_and_counts.load_interval_list_tsv_file(
        os.path.join(args.input_model_path, gcnvkernel.io_consts.default_interval_list_filename))
    contigs_set = {target.contig for target in modeling_interval_list}
    logging.info("The model contains {0} intervals and {1} contig(s)".format(
        len(modeling_interval_list), len(contigs_set)))

    # load sample names, truncated counts, and interval list from the sample read counts table
    logging.info("Loading {0} read counts file(s)...".format(len(args.read_count_tsv_files)))
    sample_names, n_st = gcnvkernel.io_intervals_and_counts.load_counts_in_the_modeling_zone(
        args.read_count_tsv_files, modeling_interval_list)

    # load read depth and ploidy metadata
    sample_metadata_collection: gcnvkernel.SampleMetadataCollection = gcnvkernel.SampleMetadataCollection()
    gcnvkernel.io_metadata.update_sample_metadata_collection_from_ploidy_determination_calls(
        sample_metadata_collection, args.ploidy_calls_path)

    # setup sample contig ploidy array
    baseline_copy_number_s = None
    for contig in contigs_set:
        if baseline_copy_number_s is None:
            baseline_copy_number_s = sample_metadata_collection.get_sample_contig_ploidy_array(
                contig, sample_names)
        else:  # the target interval list has more than one contig
            other_baseline_copy_number_s = sample_metadata_collection.get_sample_contig_ploidy_array(
                contig, sample_names)
            assert all(baseline_copy_number_s == other_baseline_copy_number_s), \
                "Contig ploidy of one of more samples varies across targets; " \
                "This can occur if modeling intervals span more than one contig and " \
                "the germline contig ploidy changes for one or more samples across the spanned " \
                "contigs; cannot continue."

    # read depth array
    read_depth_s = sample_metadata_collection.get_sample_read_depth_array(sample_names)

    # setup the inference task
    args_dict = args.__dict__

    # import model configuration and update args dict
    update_args_dict_from_exported_model(args.input_model_path, args_dict)

    # setup the case denoising and calling task
    denoising_config = gcnvkernel.DenoisingModelConfig.from_args_dict(args_dict)
    calling_config = gcnvkernel.CopyNumberCallingConfig.from_args_dict(args_dict)
    inference_params = gcnvkernel.HybridInferenceParameters.from_args_dict(args_dict)
    shared_workspace = gcnvkernel.DenoisingCallingWorkspace(
        denoising_config, calling_config, modeling_interval_list,
        n_st, baseline_copy_number_s, read_depth_s)
    initial_params_supplier = gcnvkernel.DefaultDenoisingModelInitializer(
        denoising_config, calling_config, shared_workspace)
    task = gcnvkernel.CaseDenoisingCallingTask(
        denoising_config, calling_config, inference_params,
        shared_workspace, initial_params_supplier, args.input_model_path)

    if hasattr(args, 'input_calls_path'):
        logger.info("A call path was provided to use as starting point...")
        gcnvkernel.io_denoising_calling.SampleDenoisingAndCallingPosteriorsImporter(
            shared_workspace, task.continuous_model, task.continuous_model_approx,
            args.input_calls_path)()

    if hasattr(args, 'input_adamax_path'):
        logger.info("An adamax moment path was provided to use as starting point...")
        gcnvkernel.io_adamax.AdamaxMomentEstimateImporter(task.fancy_adamax, args.input_adamax_path)()

    # go!
    task.engage()
    task.disengage()

    # save calls
    gcnvkernel.io_denoising_calling.SampleDenoisingAndCallingPosteriorsExporter(
        shared_workspace, task.continuous_model, task.continuous_model_approx, sample_names,
        args.output_calls_path)()

    # save a copy of targets in the calls path
    shutil.copy(os.path.join(args.input_model_path, gcnvkernel.io_consts.default_interval_list_filename),
                os.path.join(args.output_calls_path, gcnvkernel.io_consts.default_interval_list_filename))

    # save adamax moments
    if hasattr(args, 'output_adamax_path'):
        gcnvkernel.io_adamax.AdamaxMomentEstimateExporter(task.fancy_adamax, args.output_adamax_path)()
