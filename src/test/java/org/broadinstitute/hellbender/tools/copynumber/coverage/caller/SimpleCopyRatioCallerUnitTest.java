package org.broadinstitute.hellbender.tools.copynumber.coverage.caller;

import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.RandomGeneratorFactory;
import org.broadinstitute.hellbender.GATKBaseTest;
import org.broadinstitute.hellbender.tools.copynumber.coverage.segmentation.CopyRatioSegment;
import org.broadinstitute.hellbender.tools.copynumber.coverage.segmentation.CopyRatioSegmentCollection;
import org.broadinstitute.hellbender.tools.copynumber.formats.metadata.SampleMetadata;
import org.broadinstitute.hellbender.tools.copynumber.formats.metadata.SimpleSampleMetadata;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.param.ParamUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.broadinstitute.hellbender.tools.copynumber.coverage.caller.CalledCopyRatioSegment.Call.*;

public final class SimpleCopyRatioCallerUnitTest extends GATKBaseTest {
    private static final double NEUTRAL_SEGMENT_COPY_RATIO_THRESHOLD = 0.15;
    private static final double OUTLIER_NEUTRAL_SEGMENT_COPY_RATIO_Z_SCORE_THRESHOLD = 2.;
    private static final double CALLING_COPY_RATIO_Z_SCORE_THRESHOLD = 2.;

    private static final int RANDOM_SEED = 42;
    private static final double EPSILON = 1E-10;

    @Test
    public void testMakeCalls() {
        final RandomGenerator rng = RandomGeneratorFactory.createRandomGenerator(new Random(RANDOM_SEED));
        final SampleMetadata sampleMetadata = new SimpleSampleMetadata("Sample");
        final double segmentNoise = 0.05;
        final double intervalLog2Noise = 0.2;
        final List<Double> segmentCopyRatios = Arrays.asList(2., 3., 1., 1., 0.25, 1., 5., 1., 0., 0.5);
        final List<Integer> numIntervalsPerSegment = Arrays.asList(10, 5, 5, 100, 10, 10, 20, 10, 10, 5);
        final List<CalledCopyRatioSegment.Call> expectedCalls = Arrays.asList(
                AMPLIFICATION, AMPLIFICATION, NEUTRAL, NEUTRAL, DELETION, NEUTRAL, AMPLIFICATION, NEUTRAL, DELETION, DELETION);

        final List<CopyRatioSegment> segments = new ArrayList<>();
        for (int segmentIndex = 0; segmentIndex < numIntervalsPerSegment.size(); segmentIndex++) {
            final List<Double> intervalLog2CopyRatiosInSegment = new ArrayList<>(numIntervalsPerSegment.size());
            for (int intervalIndex = 0; intervalIndex < numIntervalsPerSegment.get(segmentIndex); intervalIndex++) {
                intervalLog2CopyRatiosInSegment.add(ParamUtils.log2(Math.max(EPSILON, segmentCopyRatios.get(segmentIndex) + rng.nextGaussian() * segmentNoise)) + intervalLog2Noise * rng.nextGaussian());
            }
            segments.add(new CopyRatioSegment(
                    new SimpleInterval("chr" + segmentIndex, 1, numIntervalsPerSegment.get(segmentIndex)),
                    intervalLog2CopyRatiosInSegment));
        }
        final CopyRatioSegmentCollection copyRatioSegments = new CopyRatioSegmentCollection(sampleMetadata, segments);

        final CalledCopyRatioSegmentCollection calledCopyRatioSegments =
                new SimpleCopyRatioCaller(copyRatioSegments,
                        NEUTRAL_SEGMENT_COPY_RATIO_THRESHOLD, OUTLIER_NEUTRAL_SEGMENT_COPY_RATIO_Z_SCORE_THRESHOLD, CALLING_COPY_RATIO_Z_SCORE_THRESHOLD)
                        .makeCalls();

        Assert.assertEquals(copyRatioSegments.getSampleName(), calledCopyRatioSegments.getSampleName());
        Assert.assertEquals(
                copyRatioSegments.getIntervals(), calledCopyRatioSegments.getIntervals());
        Assert.assertEquals(
                copyRatioSegments.getRecords().stream().map(CopyRatioSegment::getMeanLog2CopyRatio).collect(Collectors.toList()),
                calledCopyRatioSegments.getRecords().stream().map(CopyRatioSegment::getMeanLog2CopyRatio).collect(Collectors.toList()));
        Assert.assertEquals(
                calledCopyRatioSegments.getRecords().stream().map(CalledCopyRatioSegment::getCall).collect(Collectors.toList()),
                expectedCalls);
    }
}