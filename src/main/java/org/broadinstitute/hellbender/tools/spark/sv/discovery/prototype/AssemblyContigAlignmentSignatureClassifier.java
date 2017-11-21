package org.broadinstitute.hellbender.tools.spark.sv.discovery.prototype;

import htsjdk.samtools.SAMSequenceDictionary;
import org.apache.logging.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.broadcast.Broadcast;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.AlignedContig;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.AlignmentInterval;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.ChimericAlignment;
import org.broadinstitute.hellbender.tools.spark.sv.discovery.StrandSwitch;
import org.broadinstitute.hellbender.tools.spark.sv.utils.RDDUtils;
import org.broadinstitute.hellbender.utils.Utils;
import scala.Tuple2;

import java.util.*;
import java.util.stream.Collectors;

final class AssemblyContigAlignmentSignatureClassifier {

    public static final int ALIGNMENT_MAPQUAL_THREHOLD = 20;
    public static final int ALIGNMENT_READSPAN_THRESHOLD = 10;

    enum RawTypes {
        InsDel,                 // 2 alignments, indicating ins/del, simple duplication expansion/contractions
        IntraChrStrandSwitch,   // breakpoint for events involving intra-chromosome strand switch, particularly for inversion breakpoint suspects
        MappedInsertionBkpt,    // breakpoint for events involving suspected insertions where the inserted sequence could be mapped to either an 1) same-chromosome location without strand switch (tandem duplication suspect) OR 2) diff-chromosome location (MEI/dispersed dup suspect with or without strand switch)
        Cpx,                    // complex sv that seemingly have the complete event assembled
        Incomplete,             // alignment signature indicates the complete picture is not inferable from alignment signature alone
        Ambiguous,              // assembly contig has more than 1 best alignment configuration hence painting multiple pictures
        MisAssemblySuspect;     // suspected to be misassembly due to alignment signature that despite multiple alignments, no or only 1 good alignment
    }

    static EnumMap<RawTypes, JavaRDD<AssemblyContigWithFineTunedAlignments>> classifyContigs(final JavaRDD<AlignedContig> contigs,
                                                                                             final Broadcast<SAMSequenceDictionary> broadcastSequenceDictionary,
                                                                                             final Logger toolLogger) {

        // long reads with only 1 best configuration
        final JavaRDD<AlignedContig> contigsWithOnlyOneBestConfig =
                contigs.filter(lr -> !lr.hasEquallyGoodAlnConfigurations).cache();

        // primary split between 2 vs more than 2 alignments
        final Tuple2<JavaRDD<AlignedContig>, JavaRDD<AlignedContig>> twoAlignmentsOrMore =
                RDDUtils.split(contigsWithOnlyOneBestConfig, AlignedContig::hasOnly2Alignments, true);

        // special treatment for alignments with more than 2 alignments
        final JavaRDD<AlignedContig> moreThanTwoAlignments = twoAlignmentsOrMore._2;
        final EnumMap<RawTypes, JavaRDD<AssemblyContigWithFineTunedAlignments>> contigsByRawTypesFromMultiAlignment = new EnumMap<>(RawTypes.class);
        final MultipleAlignmentReclassificationResults multipleAlignmentReclassificationResults =
                reClassifyContigsWithMultipleAlignments(moreThanTwoAlignments,
                        ALIGNMENT_MAPQUAL_THREHOLD, ALIGNMENT_READSPAN_THRESHOLD);
        contigsByRawTypesFromMultiAlignment.put(RawTypes.MisAssemblySuspect,
                multipleAlignmentReclassificationResults.nonInformativeContigs);
        contigsByRawTypesFromMultiAlignment.put(RawTypes.Incomplete,
                multipleAlignmentReclassificationResults.contigsWithMoreThanTwoGoodAlignmentsButIncompletePicture);
        contigsByRawTypesFromMultiAlignment.put(RawTypes.Cpx,
                multipleAlignmentReclassificationResults.contigsWithMoreThanTwoGoodAlignments);

        // merge the two sources of contigs with two alignments
        final JavaRDD<AssemblyContigWithFineTunedAlignments> twoAlignmentsUnion =
                twoAlignmentsOrMore._1.map(AssemblyContigWithFineTunedAlignments::new)
                        .union(multipleAlignmentReclassificationResults.contigsWithTwoGoodAlignments);

        final EnumMap<RawTypes, JavaRDD<AssemblyContigWithFineTunedAlignments>> contigsByRawTypesFromTwoAlignment =
                processContigsWithTwoAlignments(twoAlignmentsUnion, broadcastSequenceDictionary);

        final EnumMap<RawTypes, JavaRDD<AssemblyContigWithFineTunedAlignments>> result =
                mergeMaps(contigsByRawTypesFromMultiAlignment, contigsByRawTypesFromTwoAlignment);

        // TODO: 11/21/17 later we may decide to salvage ambiguous contigs with some heuristic logic, but later
        // long reads with more than 1 best configurations, i.e. ambiguous raw types
        final JavaRDD<AssemblyContigWithFineTunedAlignments> ambiguous =
                contigs.filter(tig -> tig.hasEquallyGoodAlnConfigurations).map(AssemblyContigWithFineTunedAlignments::new);
        result.put(RawTypes.Ambiguous, ambiguous);

        return result;
    }

    private static EnumMap<RawTypes, JavaRDD<AssemblyContigWithFineTunedAlignments>> mergeMaps(
            final EnumMap<RawTypes, JavaRDD<AssemblyContigWithFineTunedAlignments>> one,
            final EnumMap<RawTypes, JavaRDD<AssemblyContigWithFineTunedAlignments>> two) {

        final EnumMap<RawTypes, JavaRDD<AssemblyContigWithFineTunedAlignments>> result = new EnumMap<>(RawTypes.class);
        for (final RawTypes type: RawTypes.values()) {
            final JavaRDD<AssemblyContigWithFineTunedAlignments> first = one.get(type);
            final JavaRDD<AssemblyContigWithFineTunedAlignments> second = two.get(type);

            if (first == null) {
                if (second != null) {
                    result.put(type, second);
                }
            } else {
                result.put(type, second == null ? first : first.union(second));
            }
        }
        return result;
    }

    //==================================================================================================================
    // FOR ASSEMBLY CONTIGS WITH TWO ALIGNMENTS

    static EnumMap<RawTypes, JavaRDD<AssemblyContigWithFineTunedAlignments>> processContigsWithTwoAlignments(
            final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithOnlyOneBestConfigAnd2AI,
            final Broadcast<SAMSequenceDictionary> broadcastSequenceDictionary) {

        final EnumMap<RawTypes, JavaRDD<AssemblyContigWithFineTunedAlignments>> contigsByRawTypes = new EnumMap<>(RawTypes.class);

        // first remove overlap, if any
        final JavaRDD<AssemblyContigWithFineTunedAlignments> preprocessedContigs = contigsWithOnlyOneBestConfigAnd2AI.map(
                decoratedContig -> {
                    final AlignedContig contig = decoratedContig.contig;
                    final AlignmentInterval one = contig.alignmentIntervals.get(0),
                                            two = contig.alignmentIntervals.get(1);
                    final List<AlignmentInterval> deOverlappedAlignments =
                            ContigAlignmentsModifier.removeOverlap(one, two, broadcastSequenceDictionary.getValue());

                    final AlignedContig contigWithDeOverlappedAlignments =
                            new AlignedContig(contig.contigName, contig.contigSequence, deOverlappedAlignments,
                                    contig.hasEquallyGoodAlnConfigurations);
                    return new AssemblyContigWithFineTunedAlignments(contigWithDeOverlappedAlignments);
                }
        );

        // split between the case where both alignments has unique ref span or not
        final Tuple2<JavaRDD<AssemblyContigWithFineTunedAlignments>, JavaRDD<AssemblyContigWithFineTunedAlignments>> hasFullyContainedRefSpanOrNot =
                RDDUtils.split(preprocessedContigs, AssemblyContigWithFineTunedAlignments::hasIncompletePictureFromTwoAlignments, false);
        contigsByRawTypes.put(RawTypes.Incomplete, hasFullyContainedRefSpanOrNot._1);

        // split between same chromosome mapping or not
        final Tuple2<JavaRDD<AssemblyContigWithFineTunedAlignments>, JavaRDD<AssemblyContigWithFineTunedAlignments>> sameChrOrNot =
                RDDUtils.split(hasFullyContainedRefSpanOrNot._2, AssemblyContigWithFineTunedAlignments::firstAndLastAlignmentMappedToSameChr, false);
        final JavaRDD<AssemblyContigWithFineTunedAlignments> diffChrBreakpoints = sameChrOrNot._2;

        // split between strand switch or not (NOTE BOTH SAME CHROMOSOME MAPPING)
        final Tuple2<JavaRDD<AssemblyContigWithFineTunedAlignments>, JavaRDD<AssemblyContigWithFineTunedAlignments>> strandSwitchOrNot =
                RDDUtils.split(sameChrOrNot._1, AssemblyContigAlignmentSignatureClassifier::indicatesIntraChrStrandSwitchBkpts, false);
        contigsByRawTypes.put(RawTypes.IntraChrStrandSwitch, strandSwitchOrNot._1);

        // split between ref block switch or not (NOTE BOTH SAME CHROMOSOME MAPPING AND NO STRAND SWITCH)
        final Tuple2<JavaRDD<AssemblyContigWithFineTunedAlignments>, JavaRDD<AssemblyContigWithFineTunedAlignments>> tandemDupBkptOrSimpleInsDel =
                RDDUtils.split(strandSwitchOrNot._2, AssemblyContigAlignmentSignatureClassifier::indicatesIntraChrTandemDupBkpts, false);
        contigsByRawTypes.put(RawTypes.InsDel, tandemDupBkptOrSimpleInsDel._2);

        final JavaRDD<AssemblyContigWithFineTunedAlignments> mappedInsertionBreakpointSuspects = diffChrBreakpoints.union(tandemDupBkptOrSimpleInsDel._1);
        contigsByRawTypes.put(RawTypes.MappedInsertionBkpt, mappedInsertionBreakpointSuspects);

        return contigsByRawTypes;
    }

    static boolean indicatesIntraChrStrandSwitchBkpts(final AssemblyContigWithFineTunedAlignments decoratedContig) {
        return indicatesIntraChrStrandSwitchBkpts(decoratedContig.contig);
    }

    static boolean indicatesIntraChrStrandSwitchBkpts(final AlignedContig contig) {

        if (contig.alignmentIntervals.size() != 2)
            return false;

        final AlignmentInterval first = contig.alignmentIntervals.get(0);
        final AlignmentInterval last = contig.alignmentIntervals.get(1);
        if ( !first.referenceSpan.getContig().equals(last.referenceSpan.getContig()) )
            return false;

        return first.forwardStrand ^ last.forwardStrand;
    }

    static boolean indicatesIntraChrTandemDupBkpts(final AssemblyContigWithFineTunedAlignments decoratedContig) {
        return indicatesIntraChrTandemDupBkpts(decoratedContig.contig);
    }

    static boolean indicatesIntraChrTandemDupBkpts(final AlignedContig contig) {

        if (contig.alignmentIntervals.size() != 2)
            return false;

        final AlignmentInterval first = contig.alignmentIntervals.get(0);
        final AlignmentInterval last = contig.alignmentIntervals.get(1);
        if ( !first.referenceSpan.getContig().equals(last.referenceSpan.getContig()) )
            return false;

        if (first.forwardStrand != last.forwardStrand)
            return false;

        return ChimericAlignment.isLikelySimpleTranslocation(first, last, StrandSwitch.NO_SWITCH);
    }

    //==================================================================================================================
    // FOR ASSEMBLY CONTIGS WITH MORE THAN TWO ALIGNMENTS

    /**
     * convenience struct holding 4 classes:
     *      1) non-informative contigs who after fine tuning has 0 or 1 good alignment left
     *      2) contigs with more than 2 good alignments but doesn't seem to have picture complete as defined by
     *          {@link AssemblyContigWithFineTunedAlignments#hasIncomePicture()}
     *      3) contigs with 2 good alignments and bad alignments encoded as strings
     *      4) contigs with more than 2 good alignments and seemingly have picture complete as defined by
     *          {@link AssemblyContigWithFineTunedAlignments#hasIncomePicture()}
     */
    static final class MultipleAlignmentReclassificationResults {
        final JavaRDD<AssemblyContigWithFineTunedAlignments> nonInformativeContigs;
        final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithMoreThanTwoGoodAlignments;
        final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithTwoGoodAlignments;
        final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithMoreThanTwoGoodAlignmentsButIncompletePicture;
        MultipleAlignmentReclassificationResults(final JavaRDD<AssemblyContigWithFineTunedAlignments> nonInformativeContigs,
                                                 final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithMoreThanTwoGoodAlignments,
                                                 final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithTwoGoodAlignments,
                                                 final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithMoreThanTwoGoodAlignmentsButIncompletePicture) {
            this.nonInformativeContigs = nonInformativeContigs;
            this.contigsWithMoreThanTwoGoodAlignments = contigsWithMoreThanTwoGoodAlignments;
            this.contigsWithTwoGoodAlignments = contigsWithTwoGoodAlignments;
            this.contigsWithMoreThanTwoGoodAlignmentsButIncompletePicture = contigsWithMoreThanTwoGoodAlignmentsButIncompletePicture;
        }
    }

    /**
     * Reclassify assembly contigs based on alignment fine tuning as implemented in
     * {@link #removeNonUniqueMappings(List, int, int)}.
     */
    static MultipleAlignmentReclassificationResults reClassifyContigsWithMultipleAlignments(
            final JavaRDD<AlignedContig> localAssemblyContigs,
            final int mapQThresholdInclusive, final int uniqReadLenInclusive) {

        final JavaRDD<AssemblyContigWithFineTunedAlignments> contigsWithFineTunedAlignments =
                localAssemblyContigs
                        .map(tig -> {
                            final Tuple2<List<AlignmentInterval>, List<AlignmentInterval>> goodAndBadAlignments =
                                    removeNonUniqueMappings(tig.alignmentIntervals, mapQThresholdInclusive, uniqReadLenInclusive);
                            final List<AlignmentInterval> goodAlignments = goodAndBadAlignments._1;
                            final List<String> insertionMappings =
                                    goodAndBadAlignments._2.stream().map(AlignmentInterval::toPackedString).collect(Collectors.toList());

                            return
                                    new AssemblyContigWithFineTunedAlignments(
                                            new AlignedContig(tig.contigName, tig.contigSequence, goodAlignments,
                                                    tig.hasEquallyGoodAlnConfigurations),
                                            insertionMappings);
                        });

        // first take down non-informative assembly contigs
        final Tuple2<JavaRDD<AssemblyContigWithFineTunedAlignments>, JavaRDD<AssemblyContigWithFineTunedAlignments>> informativeAndNotSo =
                RDDUtils.split(contigsWithFineTunedAlignments, AssemblyContigAlignmentSignatureClassifier::isInformative, false);
        final JavaRDD<AssemblyContigWithFineTunedAlignments> garbage = informativeAndNotSo._2;

        // assembly contigs with 2 good alignments and bad alignments encoded as strings
        final JavaRDD<AssemblyContigWithFineTunedAlignments> informativeContigs = informativeAndNotSo._1;
        final Tuple2<JavaRDD<AssemblyContigWithFineTunedAlignments>, JavaRDD<AssemblyContigWithFineTunedAlignments>> split =
                RDDUtils.split(informativeContigs, AssemblyContigAlignmentSignatureClassifier::hasOnly2GoodAlignments, false);
        final JavaRDD<AssemblyContigWithFineTunedAlignments> twoGoodAlignments = split._1;

        // assembly contigs with more than 2 good alignments: without and with a complete picture
        final JavaRDD<AssemblyContigWithFineTunedAlignments> multipleAlignments = split._2;
        final Tuple2<JavaRDD<AssemblyContigWithFineTunedAlignments>, JavaRDD<AssemblyContigWithFineTunedAlignments>> split1 =
                RDDUtils.split(multipleAlignments, AssemblyContigAlignmentSignatureClassifier::hasIncomePicture, false);
        final JavaRDD<AssemblyContigWithFineTunedAlignments> multipleAlignmentsIncompletePicture = split1._1;
        final JavaRDD<AssemblyContigWithFineTunedAlignments> multipleAlignmentsCompletePicture = split1._2;

        return new MultipleAlignmentReclassificationResults(garbage, multipleAlignmentsIncompletePicture,
                twoGoodAlignments, multipleAlignmentsCompletePicture);
    }

    // below are dummy predicates
    private static boolean isInformative(final AssemblyContigWithFineTunedAlignments contig) {
        return contig.isInformative();
    }

    private static boolean hasOnly2GoodAlignments(final AssemblyContigWithFineTunedAlignments contig) {
        return contig.hasOnly2GoodAlignments();
    }

    private static boolean hasIncomePicture(final AssemblyContigWithFineTunedAlignments contig) {
        return contig.hasIncomePicture();
    }

    /**
     * Process provided {@code originalConfiguration} of an assembly contig and split between good and bad alignments.
     * The returned pair has good alignments as its first (i.e. updated configuration),
     * and bad ones as its second (could be used for, e.g. annotate mappings of inserted sequence).
     *
     * <p>
     *     What is considered good and bad?
     *     For a particular mapping/alignment, it may offer low uniqueness in two sense:
     *     <ul>
     *         <li>
     *             low REFERENCE UNIQUENESS: meaning the sequence being mapped to match multiple locations on the reference;
     *         </li>
     *         <li>
     *             low READ UNIQUENESS: with only a very short part of the read being uniquely explained by this particular alignment;
     *         </li>
     *     </ul>
     *     Good alignments offer both high reference uniqueness and read uniqueness, as judged by the requested
     *     {@code mapQThresholdInclusive} and {@code uniqReadLenInclusive}
     *     (yes we are doing a hard-filtering but more advanced model is not our priority right now 2017-11-20).
     * </p>
     *
     * Note that "original" is meant to be possibly different from the returned configuration,
     * but DOES NOT mean the alignments of the contig as given by the aligner,
     * i.e. the configuration should be one of the best given by
     * {@link FilterLongReadAlignmentsSAMSpark#pickBestConfigurations(AlignedContig, Set, Double)}.
     */
    static Tuple2<List<AlignmentInterval>, List<AlignmentInterval>> removeNonUniqueMappings(final List<AlignmentInterval> originalConfiguration,
                                                                                            final int mapQThresholdInclusive,
                                                                                            final int uniqReadLenInclusive) {
        Utils.validateArg(originalConfiguration.size() > 2,
                "assumption that input configuration to be fine tuned has more than 2 alignments is violated.\n" +
                        originalConfiguration.stream().map(AlignmentInterval::toPackedString).collect(Collectors.toList()));

        // two pass, each focusing on removing the alignments of a contig that offers low uniqueness in one sense:

        // first pass is for removing alignments with low REFERENCE UNIQUENESS, using low mapping quality as the criterion
        final List<AlignmentInterval> selectedAlignments = new ArrayList<>(originalConfiguration.size()),
                lowUniquenessMappings = new ArrayList<>(originalConfiguration.size());

        for (final AlignmentInterval alignment : originalConfiguration) {
            if (alignment.mapQual >= mapQThresholdInclusive)
                selectedAlignments.add(alignment);
            else
                lowUniquenessMappings.add(alignment);
        }

        // second pass, the slower one, is to remove alignments offering low READ UNIQUENESS,
        // i.e. with only a very short part of the read being uniquely explained by this particular alignment;
        // the steps are:
        //      search bi-directionally until cannot find overlap any more,
        //      subtract the overlap from the distance covered on the contig by the alignment.
        //      This gives unique read region it explains.
        //      If this unique read region is "short": shorter than {@code uniqReadLenInclusive}), drop it.

        // each alignment has an entry of a tuple2, one for max overlap front, one for max overlap rear,
        // max overlap front is a tuple2 registering the index and overlap bases count
        final List<Tuple2<Tuple2<Integer, Integer>, Tuple2<Integer, Integer>>> maxOverlapMap =
                getMaxOverlapPairs(selectedAlignments);

        final List<Integer> idxToRemove = new ArrayList<>(selectedAlignments.size());
        for (int i = 0; i < selectedAlignments.size(); ++i) {
            final AlignmentInterval cur = selectedAlignments.get(i);
            final Tuple2<Tuple2<Integer, Integer>, Tuple2<Integer, Integer>> maxOverlapFrontAndRear = maxOverlapMap.get(i);
            final int maxOverlapFront = Math.max(0, maxOverlapFrontAndRear._1._2);
            final int maxOverlapRead = Math.max(0, maxOverlapFrontAndRear._2._2);

            final int uniqReadSpan = cur.endInAssembledContig - cur.startInAssembledContig + 1 - maxOverlapFront - maxOverlapRead;
            if (uniqReadSpan < uniqReadLenInclusive)
                idxToRemove.add(i);
        }

        if ( idxToRemove.isEmpty() )
            return new Tuple2<>(selectedAlignments, lowUniquenessMappings);

        // removing in reverse order so that iterators are not invalidated if we were to remove from start
        final ListIterator<Integer> rit = idxToRemove.listIterator(idxToRemove.size());
        while (rit.hasPrevious()) {
            selectedAlignments.remove( rit.previous().intValue() );
        }

        return new Tuple2<>(selectedAlignments, lowUniquenessMappings);
    }

    /**
     * Each alignment has an entry of a tuple2, one for max overlap front, one for max overlap rear,
     * max overlap front is a tuple2 registering the index and overlap bases count
     */
    private static List<Tuple2<Tuple2<Integer, Integer>, Tuple2<Integer, Integer>>> getMaxOverlapPairs(final List<AlignmentInterval> selectedAlignments) {

        final List<Tuple2<Tuple2<Integer, Integer>, Tuple2<Integer, Integer>>> maxOverlapMap =
                new ArrayList<>(Collections.nCopies(selectedAlignments.size(), new Tuple2<>(new Tuple2<>(-1, -1), new Tuple2<>(-1, -1))));

        for(int i = 0; i < selectedAlignments.size() - 1; ++i) {
            final AlignmentInterval cur = selectedAlignments.get(i);
            int maxOverlap = -1;
            int maxOverlapIdx = -1;
            for (int j = i + 1; j < selectedAlignments.size(); ++j) {
                final int overlap = AlignmentInterval.overlapOnContig(cur, selectedAlignments.get(j));
                if (overlap > 0) {
                    maxOverlap = Math.max(maxOverlap, overlap);
                    maxOverlapIdx = j;
                } else { // following ones, as guaranteed by the ordering of alignments in the contig, cannot overlap
                    break;
                }
            }
            if (maxOverlap > 0){
                // first set the max_overlap_rear of the current alignment
                Tuple2<Tuple2<Integer, Integer>, Tuple2<Integer, Integer>> oldValue = maxOverlapMap.get(i);
                maxOverlapMap.set(i, new Tuple2<>(oldValue._1, new Tuple2<>(maxOverlapIdx, maxOverlap))); // maxOverlapIdx cannot be -1 here
                // then conditionally set the max_overlap_front of the maxOverlapIdx-th alignment that maximally overlaps with the current alignment
                oldValue = maxOverlapMap.get(maxOverlapIdx);
                if (oldValue._1._2 < maxOverlap)
                    maxOverlapMap.set(maxOverlapIdx, new Tuple2<>(new Tuple2<>(i, maxOverlap), oldValue._2));
            }
        }
        return maxOverlapMap;
    }


    //==================================================================================================================

    // TODO: 11/17/17 salvation on assembly contigs that 1) has ambiguous "best" configuration, and 2) has incomplete picture; and flag accordingly

}
