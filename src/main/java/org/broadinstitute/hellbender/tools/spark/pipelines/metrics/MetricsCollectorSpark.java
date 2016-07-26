package org.broadinstitute.hellbender.tools.spark.pipelines.metrics;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.metrics.Header;
import org.apache.spark.api.java.JavaRDD;
import org.broadinstitute.hellbender.engine.AuthHolder;
import org.broadinstitute.hellbender.engine.filters.ReadFilter;
import org.broadinstitute.hellbender.engine.filters.ReadFilterLibrary;
import org.broadinstitute.hellbender.metrics.MetricsArgumentCollection;
import org.broadinstitute.hellbender.utils.read.GATKRead;

import java.io.Serializable;
import java.util.List;

/**
 * Each metrics collector has to be able to run from 4 different contexts:
 *
 *   - a standalone walker tool
 *   - the {@link org.broadinstitute.hellbender.tools.picard.analysis.CollectMultipleMetrics} walker tool
 *   - a standalone Spark tool
 *   - the {@link org.broadinstitute.hellbender.tools.spark.pipelines.metrics.CollectMultipleMetricsSpark} tool
 *
 * In order to allow a single collector implementation to be shared across all of these
 * contexts (standalone and CollectMultiple, Spark and non-Spark), collectors should be
 * factored into the following classes, where X in the class names represents the specific
 * type of metrics being collected:
 *
 *   XMetrics extends {@link htsjdk.samtools.metrics.MetricBase}: defines the aggregate metrics that we're trying to collect
 *   XMetricsArgumentCollection: defines parameters for XMetrics, extends {@link org.broadinstitute.hellbender.metrics.MetricsArgumentCollection}
 *   XMetricsCollector: processes a single read, and has a reduce/combiner
 *       For multi level collectors, XMetricsCollector is composed of several classes:
 *           XMetricsCollector extends {@link org.broadinstitute.hellbender.metrics.MultiLevelReducibleCollector}<
 *              XMetrics, HISTOGRAM_KEY, XMetricsCollectorArgs, XMetricsPerUnitCollector>
 *           XMetricsPerUnitCollector: per level collector, implements
 *              {@link org.broadinstitute.hellbender.metrics.PerUnitMetricCollector}<XMetrics, HISTOGRAM_KEY, XMetricsCollectorArgs> (requires a combiner)
 *           XMetricsCollectorArgs per-record argument (type argument for {@link org.broadinstitute.hellbender.metrics.MultiLevelReducibleCollector})
 *   XMetricsCollectorSpark: adapter/bridge between RDD and the (read-based) XMetricsCollector,
 *          implements {@link org.broadinstitute.hellbender.tools.spark.pipelines.metrics.MetricsCollectorSpark}<XMetricsArgumentCollection>
 *   CollectXMetrics extends {@link org.broadinstitute.hellbender.tools.picard.analysis.SinglePassSamProgram}
 *   CollectXMetricsSpark extends {@link org.broadinstitute.hellbender.tools.spark.pipelines.metrics.MetricsCollectorSparkTool}<MyMetricsArgumentCollection>
 *
 * The following schematic shows the general relationships of these collector component classes
 * in the context of various tools, with the arrows indicating a "delegates to" relationship
 * via composition or inheritance:
 *
 *     CollectXMetrics    CollectMultipleMetrics
 *                \               /
 *                \              /
 *                v             v
 *      _______________________________________
 *      |          XMetricsCollector =========|=========> MultiLevelReducibleCollector
 *      |                 |                   |                      |
 *      |                 V                   |                      |
 *      |             XMetrics                |                      V
 *      | XMetricsCollectorArgumentCollection |             PerUnitXMetricCollector
 *      ---------------------------------------
 *                       ^
 *                       |
 *                       |
 *             XMetricsCollectorSpark
 *                ^              ^
 *               /               \
 *              /                \
 *    CollectXMetricsSpark  CollectMultipleMetricsSpark
 *
 *
 * The general lifecycle of a Spark collector (XMetricsCollectorSpark in the diagram
 * above) looks like this:
 *
 *     CollectorType collector = new CollectorType<CollectorArgType>()
 *     CollectorArgType args = // get metric-specific input arguments
 *
 *     // pass the input arguments to the collector for initialization
 *     collector.initialize(args);
 *
 *     ReadFilter filter == collector.getReadFilter(samFileHeader);
 *     collector.collectMetrics(
 *         getReads().filter(filter),
 *         samFileHeader
 *     );
 *     collector.saveMetrics(getReadSourceName(), getAuthHolder());
 *
 */
public interface MetricsCollectorSpark<T extends MetricsArgumentCollection> extends Serializable
{
    public static final long serialVersionUID = 1L;

    /**
     * Return whether or not this collector requires a reference. Default implementation
     * returns false.
     * @return true if this collector requires that a reference be provided, otherwise false.
     */
    default boolean requiresReference() { return false;}

    /**
     * Return the sort order this collect requires. Collector consumers will validate
     * the expected sort order. If no specific sort order is required, return
     * <code>SortOrder.unsorted</code>. Default implementation returns
     * <code>SortOrder.coordinate</code>
     * @return SortOrder required for this collector or <code>SortOrder.unsorted</code>
     * to indicate any sort order is acceptable.
     */
    default SortOrder getExpectedSortOrder() { return SortOrder.coordinate; }

    /**
     * Give the collector's input arguments to the collector (if the collector
     * is being driven by a standalone tool. This method will always be called
     * before either getReadFilter (so the collector can use the arguments to
     * initialize the readFilter) or collectMetrics.
     * @param inputArgs an object that contains the argument values to be used for
     *                  the collector
     * @param samHeader the SAMFileHeader for the input
     * @param defaultHeaders default metrics headers from the containing tool
     */
    void initialize(T inputArgs, SAMFileHeader samHeader, List<Header> defaultHeaders);

    /**
     * Return the read filter required for this collector. The default implementation
     * returns a no-op read filter that allows all reads. Collectors that require
     * a more specific filter criteria should return an instance of the required filter.
     * @param samHeader the SAMFileHeader for the input BAM
     * @return the read filter required for this collector
     */
    default ReadFilter getReadFilter(SAMFileHeader samHeader) {
        return ReadFilterLibrary.ALLOW_ALL_READS;
    }

    /**
     * Do the actual metrics collection on the provided RDD.
     * @param filteredReads The reads to be analyzed for this collector. The reads will have already
     *                      been filtered by this collector's read filter.
     * @param samHeader The SAMFileHeader associated with the reads in the input RDD.
     */
    void collectMetrics(JavaRDD<GATKRead> filteredReads, SAMFileHeader samHeader);

    /**
     * This method is called after collectMetrics has returned and
     * all Spark partitions have completed metrics collection. This
     * gives the collector to serialize the resulting metrics, usually
     * to a metrics file.
     * @param inputBaseName base name of the input file
     * @param authHolder authentication info. May be null.
     */
    default void saveMetrics(String inputBaseName, AuthHolder authHolder) { }
}