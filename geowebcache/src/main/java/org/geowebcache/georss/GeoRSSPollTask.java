package org.geowebcache.georss;

import java.io.IOException;
import java.net.URL;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.grid.GridSubset;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.layer.updatesource.GeoRSSFeedDefinition;
import org.geowebcache.mime.MimeException;
import org.geowebcache.mime.MimeType;
import org.geowebcache.rest.GWCTask;
import org.geowebcache.rest.seed.RasterMask;
import org.geowebcache.rest.seed.SeedRestlet;
import org.geowebcache.storage.DiscontinuousTileRange;

/**
 * A task to run a GeoRSS feed poll and launch the seeding process
 * 
 */
class GeoRSSPollTask implements Runnable {

    private static final Log logger = LogFactory.getLog(GeoRSSPollTask.class);

    private final PollDef poll;

    private final SeedRestlet seedRestlet;

    public GeoRSSPollTask(final PollDef poll, final SeedRestlet seedRestlet) {
        this.poll = poll;
        this.seedRestlet = seedRestlet;
    }
    
    /**
     * Called by the thread executor when the poll def's interval has elapsed (or as soon as
     * possible after it elapsed).
     */
    public void run() {
        /*
         * This method cannot throw an exception or the thread scheduler will discard the task.
         * Instead, if an error happens when polling we log the exception and hope for the next run
         * to work?
         */
        try {
            runPollAndLaunchSeed();
        } catch (Exception e) {
            logger.error("Error encountered trying to poll the GeoRSS feed "
                    + poll.getPollDef().getFeedUrl()
                    + ". Another attempt will be made after the poll interval of "
                    + poll.getPollDef().getPollIntervalStr(), e);
        } catch (OutOfMemoryError error) {
            System.gc();
            logger.fatal("Out of memory error processing poll " + poll.getPollDef()
                    + ". Need to reduce the maxMaskLevel param or increase system memory."
                    + " Poll disabled.", error);
            throw error;
        }
    }

    private void runPollAndLaunchSeed() throws IOException {
        final TileLayer layer = poll.getLayer();
        final GeoRSSFeedDefinition pollDef = poll.getPollDef();

        logger.info("Polling GeoRSS feed for layer " + layer.getName() + ": " + pollDef.toString());

        final String gridSetId = pollDef.getGridSetId();
        final URL feedUrl = new URL(pollDef.getFeedUrl());

        logger.debug("Getting GeoRSS reader for " + feedUrl.toExternalForm());
        final GeoRSSReaderFactory geoRSSReaderFactory = new GeoRSSReaderFactory();
        final GeoRSSReader geoRSSReader = geoRSSReaderFactory.createReader(feedUrl);

        logger.debug("Got reader for " + pollDef.getFeedUrl()
                + ". Creating geometry filter matrix for gridset " + gridSetId + " on layer "
                + layer.getName());

        final int maxMaskLevel = pollDef.getMaxMaskLevel();
        final GeoRSSTileRangeBuilder matrixBuilder = new GeoRSSTileRangeBuilder(layer, gridSetId, maxMaskLevel);

        logger.debug("Creating tile range mask based on GeoRSS feed's geometries from "
                + feedUrl.toExternalForm() + " for " + layer.getName());

        final TileGridFilterMatrix tileRangeMask = matrixBuilder.buildTileRangeMask(geoRSSReader);
        logger.debug("Created tile range mask based on GeoRSS geometry feed from " + pollDef
                + " for " + layer.getName() + ". Calculating number of affected tiles...");

        final long totalTilesSet = tileRangeMask.getTotalTilesSet();
        if (totalTilesSet > 0) {
            logger.info(pollDef + " for " + layer.getName() + " affected " + totalTilesSet
                    + ". Launching reseed process...");
        } else {
            logger.info(pollDef + " for " + layer.getName()
                    + " did not affect any tile. No need to reseed.");
            return;
        }

        launchSeeding(layer, pollDef, gridSetId, tileRangeMask);

        logger.info("Seeding process for tiles affected by feed " + feedUrl.toExternalForm()
                + " successfully launched.");
    }

    private void launchSeeding(final TileLayer layer, final GeoRSSFeedDefinition pollDef,
            final String gridSetId, final TileGridFilterMatrix tileRangeMask) {
        
        GridSubset gridSub = layer.getGridSubset(gridSetId);
        
        final String mimeFormat = pollDef.getMimeFormat();
        
        MimeType mime = null;
        try {
            mime = MimeType.createFromFormat(mimeFormat);
        } catch (MimeException e) {
            logger.error("MimeType "+mimeFormat+" not recognized, "
                    +"aborting GeoRSS update! Check geowebcache.xml");
        }
        
        long[][] coveredBounds = tileRangeMask.getCoveredBounds();
        
        coveredBounds = gridSub.expandToMetaFactors(coveredBounds, layer.getMetaTilingFactors());
        
        RasterMask rasterMask = new RasterMask(tileRangeMask.getByLevelMasks(), coveredBounds);
        
        DiscontinuousTileRange dtr = new DiscontinuousTileRange(
                layer.getName(), gridSetId, 
                gridSub.getZoomStart(), gridSub.getZoomStop(), 
                rasterMask,
                mime, null);
                        
        GWCTask[] tasks = seedRestlet.createTasks(dtr, layer, GWCTask.TYPE.TRUNCATE, 1, false);
        
        // We do the truncate synchronously
        try {
            tasks[0].doAction();
        } catch (GeoWebCacheException e) {
            logger.error("Problem truncating based on GeoRSS feed: " + e.getMessage());
        }
        
        // Then we seed
        tasks = seedRestlet.createTasks(dtr, layer, GWCTask.TYPE.SEED, pollDef.getSeedingThreads(), false);
       
        seedRestlet.dispatchTasks(tasks);
    }
}