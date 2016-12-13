package org.broadinstitute.hellbender.tools.walkers.annotator;

import com.google.common.collect.ImmutableMap;
import htsjdk.samtools.Cigar;
import htsjdk.samtools.TextCigarCodec;
import htsjdk.variant.variantcontext.*;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific.AS_RankSumTest;
import org.broadinstitute.hellbender.tools.walkers.annotator.allelespecific.AS_ReadPosRankSumTest;
import org.broadinstitute.hellbender.utils.MannWhitneyU;
import org.broadinstitute.hellbender.utils.genotyper.*;
import org.broadinstitute.hellbender.utils.read.ArtificialReadUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.test.BaseTest;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ReadPosRankSumTestUnitTest extends BaseTest {
    private final String sample1 = "NA1";
    private final String sample2 = "NA2";
    private static final String CONTIG = "1";

    private static final Allele REF = Allele.create("T", true);
    private static final Allele ALT = Allele.create("A", false);

    private VariantContext makeVC(final long position) {
        final double[] genotypeLikelihoods1 = {30,0,190};
        final GenotypesContext testGC = GenotypesContext.create(2);
        // sample1 -> A/T with GQ 30
        testGC.add(new GenotypeBuilder(sample1).alleles(Arrays.asList(REF, ALT)).PL(genotypeLikelihoods1).GQ(30).make());
        // sample2 -> A/T with GQ 40
        testGC.add(new GenotypeBuilder(sample2).alleles(Arrays.asList(REF, ALT)).PL(genotypeLikelihoods1).GQ(40).make());

        return (new VariantContextBuilder())
                .alleles(Arrays.asList(REF, ALT)).chr(CONTIG).start(position).stop(position).genotypes(testGC).make();
    }

    private GATKRead makeRead(final int start, final int mq) {
        Cigar cigar = TextCigarCodec.decode("10M");
        final GATKRead read = ArtificialReadUtils.createArtificialRead(cigar);
        read.setMappingQuality(mq);
        read.setPosition(CONTIG, start);
        return read;
    }

    @DataProvider(name = "dataReadPos")
    private Object[][] dataReadPos(){
         return new Object[][]{
                 {},
                 {new AS_ReadPosRankSumTest(), GATKVCFConstants.AS_READ_POS_RANK_SUM_KEY, GATKVCFConstants.AS_RAW_READ_POS_RANK_SUM_KEY},
         };
    }

    @Test
    public void testReadPos(){
        final InfoFieldAnnotation ann = new ReadPosRankSumTest();
        final String key =  GATKVCFConstants.READ_POS_RANK_SUM_KEY;

        final int[] startAlts = {3, 4};
        final int[] startRefs = {1, 2};
        final List<GATKRead> refReads = Arrays.asList(makeRead(startRefs[0], 30), makeRead(startRefs[1], 30));
        final List<GATKRead> altReads = Arrays.asList(makeRead(startAlts[0], 30), makeRead(startAlts[1], 30));
        final ReadLikelihoods<Allele> likelihoods =
                AnnotationArtificialData.makeLikelihoods(sample1, refReads, altReads, -100.0, -100.0, REF, ALT);

        Assert.assertEquals(ann.getDescriptions().size(), 1);
        Assert.assertEquals(ann.getDescriptions().get(0).getID(), key);
        Assert.assertEquals(ann.getKeyNames().size(), 1);
        Assert.assertEquals(ann.getKeyNames().get(0), key);

        final ReferenceContext ref= null;

        final long position = 5L;  //middle of the read
        final VariantContext vc= makeVC(position);

        final Map<String, Object> annotate = ann.annotate(ref, vc, likelihoods);
        final double val= MannWhitneyU.runOneSidedTest(false,
                Arrays.asList(position - startAlts[0], position - startAlts[1]),
                Arrays.asList(position - startRefs[0], position - startRefs[1])).getLeft();
        final String valStr= String.format("%.3f", val);
        Assert.assertEquals(annotate.get(key), valStr);


        final long positionEnd = 8L;  //past middle
        final VariantContext vcEnd= makeVC(positionEnd);

        //Note: past the middle of the read we compute the position from the end.
        final Map<String, Object> annotateEnd = ann.annotate(ref, vcEnd, likelihoods);
        final double valEnd= MannWhitneyU.runOneSidedTest(false,
                Arrays.asList(startAlts[0], startAlts[1]),
                Arrays.asList(startRefs[0], startRefs[1])).getLeft();
        final String valStrEnd= String.format("%.3f", valEnd);
        Assert.assertEquals(annotateEnd.get(key), valStrEnd);

        final long positionPastEnd = 20L;  //past middle
        final VariantContext vcPastEnd= makeVC(positionPastEnd);

        //Note: past the end of the read, there's nothing
        final Map<String, Object> annotatePastEnd = ann.annotate(ref, vcPastEnd, likelihoods);
        Assert.assertTrue(annotatePastEnd.isEmpty());
    }

    @Test
    public void testReadPos_Raw(){
        final AS_RankSumTest ann= new AS_ReadPosRankSumTest();
        final String key1 = GATKVCFConstants.AS_RAW_READ_POS_RANK_SUM_KEY;
        final String key2 = GATKVCFConstants.AS_READ_POS_RANK_SUM_KEY;
        final int[] startAlts = {3, 4};
        final int[] startRefs = {1, 2};
        final int readLength = 10;

        final List<GATKRead> refReads = Arrays.asList(makeRead(startRefs[0], 30), makeRead(startRefs[1], 30));
        final List<GATKRead> altReads = Arrays.asList(makeRead(startAlts[0], 30), makeRead(startAlts[1], 30));
        final ReadLikelihoods<Allele> likelihoods =
                AnnotationArtificialData.makeLikelihoods(sample1, refReads, altReads, -100.0, -100.0, REF, ALT);

        Assert.assertEquals(ann.getDescriptions().size(), 1);
        Assert.assertEquals(ann.getDescriptions().get(0).getID(), key1);
        Assert.assertEquals(ann.getKeyNames().size(), 1);
        Assert.assertEquals(ann.getKeyNames().get(0), key2);

        final ReferenceContext ref= null;

        final long position = 5L;  //middle of the read
        final VariantContext vc= makeVC(position);

        final Map<String, Object> annotateRaw = ann.annotateRawData(ref, vc, likelihoods);
        final Map<String, Object> annotateNonRaw = ann.annotate(ref, vc, likelihoods);
        final String expected = startAlts[0] + ",1," + startAlts[1] + ",1" + AS_RankSumTest.PRINT_DELIM + startRefs[0] + ",1," + startRefs[1] + ",1";
        Assert.assertEquals(annotateRaw.get(key1), expected);
        Assert.assertEquals(annotateNonRaw.get(key1), expected);


        final long positionEnd = 8L;  //past middle
        final VariantContext vcEnd= makeVC(positionEnd);

        //Note: past the middle of the read we compute the position from the end.
        final Map<String, Object> annotateEndRaw = ann.annotateRawData(ref, vcEnd, likelihoods);
        final Map<String, Object> annotateEndNonRaw = ann.annotate(ref, vcEnd, likelihoods);
        final String refS = (startRefs[0]+readLength-positionEnd-1)+ ",1," +(startRefs[1]+readLength-positionEnd-1) + ",1";
        final String altS = (positionEnd-startAlts[1]) + ",1," + (positionEnd-startAlts[0]) + ",1";
        Assert.assertEquals(annotateEndRaw.get(key1), refS + AS_RankSumTest.PRINT_DELIM + altS );
        Assert.assertEquals(annotateEndNonRaw.get(key1), refS + AS_RankSumTest.PRINT_DELIM + altS );

        final long positionPastEnd = 20L;  //past middle
        final VariantContext vcPastEnd= makeVC(positionPastEnd);

        //Note: past the end of the read, there's nothing
        final Map<String, Object> annotatePastEndRaw = ann.annotateRawData(ref, vcPastEnd, likelihoods);
        final Map<String, Object> annotatePastEndNonRaw = ann.annotate(ref, vcPastEnd, likelihoods);
        Assert.assertTrue(annotatePastEndRaw.isEmpty());
        Assert.assertTrue(annotatePastEndNonRaw.isEmpty());
    }
}