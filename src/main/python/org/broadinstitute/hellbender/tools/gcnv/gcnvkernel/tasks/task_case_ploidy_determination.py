import logging

from .inference_task_base import HybridInferenceTask, HybridInferenceParameters
from ..models.model_ploidy import PloidyModelConfig, PloidyModel, PloidyWorkspace
from ..inference.sample_specific_opt import SampleSpecificAdamax
from ..io.io_ploidy import PloidyModelImporter

_logger = logging.getLogger(__name__)


class CasePloidyInferenceTask(HybridInferenceTask):
    def __init__(self,
                 hybrid_inference_params: HybridInferenceParameters,
                 ploidy_config: PloidyModelConfig,
                 ploidy_workspace: PloidyWorkspace,
                 input_model_path: str):
        # the caller and sampler is the same as the cohort tool
        from .task_cohort_ploidy_determination import PloidyCaller, PloidyEmissionSampler

        _logger.info("Instantiating the germline contig ploidy determination model...")
        ploidy_model = PloidyModel(ploidy_config, ploidy_workspace)

        _logger.info("Instantiating the ploidy emission sampler...")
        ploidy_emission_sampler = PloidyEmissionSampler(hybrid_inference_params, ploidy_model, ploidy_workspace)

        _logger.info("Instantiating the ploidy caller...")
        ploidy_caller = PloidyCaller(hybrid_inference_params, ploidy_workspace)

        elbo_normalization_factor = ploidy_workspace.num_samples * ploidy_workspace.num_contigs
        opt = SampleSpecificAdamax(hybrid_inference_params.learning_rate,
                                   hybrid_inference_params.adamax_beta1,
                                   hybrid_inference_params.adamax_beta2)

        super().__init__(hybrid_inference_params, ploidy_model, ploidy_emission_sampler, ploidy_caller,
                         elbo_normalization_factor=elbo_normalization_factor,
                         advi_task_name="denoising",
                         calling_task_name="ploidy calling",
                         custom_optimizer=opt)

        self.ploidy_config = ploidy_config
        self.ploidy_workspace = ploidy_workspace

        _logger.info("Loading the model and updating the instantiated model and workspace...")
        PloidyModelImporter(self.continuous_model, self.continuous_model_approx, input_model_path)()

    def disengage(self):
        pass