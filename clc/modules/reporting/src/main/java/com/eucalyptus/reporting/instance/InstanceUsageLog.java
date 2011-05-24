package com.eucalyptus.reporting.instance;

import java.util.*;

import org.apache.log4j.Logger;

import com.eucalyptus.configurable.ConfigurableClass;
import com.eucalyptus.entities.EntityWrapper;
import com.eucalyptus.reporting.Period;

/**
 * <p>InstanceUsageLog is the main API for accessing usage information which
 * has been stored in the usage log.
 * 
 * <p>The usage data in logs is <i>sampled</i>, meaning data is collected
 * every <i>n</i> seconds and written. As a result, some small error will
 * be introduced if the boundaries of desired periods (ie months) do not
 * exactly correspond to the boundaries of the samples. In that case, the
 * reporting mechanism will be unable to determine how much of the usage in
 * a sample belongs to which of the two periods whose boundaries it crosses,
 * so it will assign usage to one period based on a rule.
 * 
 * <p>Very recent information (within the prior five minutes, for example)
 * may not have been acquired yet, in which case, an empty period or a
 * period with incomplete information may be returned.
 * 
 * @author tom.werges
 */
@ConfigurableClass(root="instanceLog", alias="basic", description="Configuration for instance usage sampling and logging", singleton=true)
public class InstanceUsageLog
{
	private static Logger log = Logger.getLogger( InstanceUsageLog.class );

	private static InstanceUsageLog singletonInstance = null;

	private InstanceUsageLog()
	{
	}

	public static synchronized InstanceUsageLog getInstanceUsageLog()
	{
		if (singletonInstance==null) {
			singletonInstance = new InstanceUsageLog();
		}
		return singletonInstance;
	}


	/**
	 * Permanently purges data older than a certain timestamp from the log. 
	 */
	public void purgeLog(long earlierThanMs)
	{
		log.info(String.format("purge earlierThan:%d ", earlierThanMs));

		EntityWrapper<InstanceAttributes> entityWrapper = EntityWrapper.get(InstanceAttributes.class);
		try {
			
			/* Delete older instance snapshots
			 */
			entityWrapper.createSQLQuery("DELETE FROM instance_usage_snapshot WHERE timestamp_ms < ?")
				.setLong(0, new Long(earlierThanMs))
				.executeUpdate();

			/* Delete all reporting instances which no longer have even a
			 * a single corresponding instance usage snapshot, using
			 * MySQL's fancy multi-table delete with left outer join syntax.
			 */
			entityWrapper.createSQLQuery(
					"DELETE reporting_instance" 
					+ " FROM reporting_instance"
					+ " LEFT OUTER JOIN instance_usage_snapshot"
					+ " ON reporting_instance.uuid = instance_usage_snapshot.uuid"
					+ " WHERE instance_usage_snapshot.uuid IS NULL")
				.executeUpdate();
			entityWrapper.commit();
		} catch (Exception ex) {
			log.error(ex);
			entityWrapper.rollback();
			throw new RuntimeException(ex);
		}
	}
	
	
	/**
	 * <p>Find the latest snapshot before timestampMs, by iteratively
	 * querying before the period beginning, moving backward in exponentially
	 * growing intervals
	 */
	long findLatestAllSnapshotBefore(long timestampMs)
	{
		long foundTimestampMs = 0l;

		EntityWrapper<InstanceUsageSnapshot> entityWrapper = null;
		try {

	    	final long oneHourMs = 60*60*1000;
			for ( int i=2 ;
				  (timestampMs - oneHourMs*(long)i) > 0 ;
				  i=(int)Math.pow(i, 2))
			{
				entityWrapper = EntityWrapper.get(InstanceUsageSnapshot.class);
				
				long startingMs = timestampMs - (oneHourMs*i);
				log.info("Searching for latest timestamp before beginning:" + startingMs);
				@SuppressWarnings("rawtypes")
				List iuses =
					entityWrapper.createQuery(
						"from InstanceUsageSnapshot as ius"
						+ " WHERE ius.timestampMs > ?"
						+ " AND ius.timestampMs < ?")
						.setLong(0, new Long(startingMs))
						.setLong(1, new Long(timestampMs))
						.list();
				for (Object obj: iuses) {
					InstanceUsageSnapshot snapshot = (InstanceUsageSnapshot) obj;
					foundTimestampMs = snapshot.getTimestampMs();
				}
				entityWrapper.commit();
				if (foundTimestampMs != 0l) break;
			}
			log.info("Found latest timestamp before beginning:"
					+ foundTimestampMs);			
		} catch (Exception ex) {
			log.error(ex);
			if (entityWrapper != null) entityWrapper.rollback();
			throw new RuntimeException(ex);
		}
		
		return foundTimestampMs;
	}

	
	
	/**
	 * <p>Gather a Map of all Instance resource usage for a period.
	 */
    public Map<InstanceSummaryKey, InstanceUsageSummary> getUsageSummaryMap(Period period)
    {
    	log.info("GetUsageSummaryMap period:" + period);

		final Map<InstanceSummaryKey, InstanceUsageSummary> usageMap =
    		new HashMap<InstanceSummaryKey, InstanceUsageSummary>();

		//Key is uuid
		Map<String,InstanceDataAccumulator> dataAccumulatorMap =
			new HashMap<String,InstanceDataAccumulator>();

		EntityWrapper<InstanceUsageSnapshot> entityWrapper =
			EntityWrapper.get(InstanceUsageSnapshot.class);
		try {
			

			/* Start query from last snapshot before report beginning, and
			 * iterate through the data until after the end. We'll truncate and
			 * extrapolate.
			 */
			long latestSnapshotBeforeMs =
				findLatestAllSnapshotBefore(period.getBeginningMs());
			long afterEnd = period.getEndingMs() 
					+ ((period.getBeginningMs()-latestSnapshotBeforeMs)*2);

			
			@SuppressWarnings("rawtypes")
			List list = entityWrapper.createQuery(
					"from InstanceAttributes as ia, InstanceUsageSnapshot as ius"
					+ " where ia.uuid = ius.uuid"
					+ " and ius.timestampMs > ?"
					+ " and ius.timestampMs < ?")
					.setLong(0, latestSnapshotBeforeMs)
					.setLong(1, afterEnd)
					.list();
			
			for (Object obj: list) {

				Object[] row = (Object[]) obj;
				InstanceAttributes insAttrs = (InstanceAttributes) row[0];
				InstanceUsageSnapshot snapshot = (InstanceUsageSnapshot) row[1];

				//log.info("Found row attrs:" + insAttrs + " snapshot:" + snapshot);
				
				String uuid = insAttrs.getUuid();
				if ( !dataAccumulatorMap.containsKey( uuid ) ) {
					InstanceDataAccumulator accumulator =
						new InstanceDataAccumulator(insAttrs, snapshot, period);
					dataAccumulatorMap.put(uuid, accumulator);
				} else {
					InstanceDataAccumulator accumulator =
						dataAccumulatorMap.get( uuid );
					accumulator.update( snapshot );
				}

			}

			for (String uuid: dataAccumulatorMap.keySet()) {
				//log.info("Instance uuid:" + uuid);
				InstanceDataAccumulator accumulator =
					dataAccumulatorMap.get(uuid);
				InstanceSummaryKey key =
					new InstanceSummaryKey(accumulator.getInstanceAttributes());
				if (! usageMap.containsKey(key)) {
					usageMap.put(key, new InstanceUsageSummary());
				}
				InstanceUsageSummary ius = usageMap.get(key);
				ius.addDiskIoMegs(accumulator.getDiskIoMegs());
				ius.addNetworkIoMegs(accumulator.getNetIoMegs());
				ius.sumFromPeriodType(accumulator.getDurationPeriod(),
						accumulator.getInstanceAttributes().getInstanceType());
				
			}

			entityWrapper.commit();
		} catch (Exception ex) {
			log.error(ex);
			entityWrapper.rollback();
			throw new RuntimeException(ex);
		}

		
//		log.info("Printing usageMap");
//		for (InstanceSummaryKey key: usageMap.keySet()) {
//			log.info("key:" + key + " summary:" + usageMap.get(key));
//		}
//		
        return usageMap;
    }

	
    private class InstanceDataAccumulator
    {
    	private final InstanceAttributes insAttrs;
    	private final InstanceUsageSnapshot firstSnapshot;
    	private InstanceUsageSnapshot lastSnapshot;
    	private Period period;
    	
    	public InstanceDataAccumulator(InstanceAttributes insAttrs,
    			InstanceUsageSnapshot snapshot, Period period)
		{
			super();
			this.insAttrs = insAttrs;
			this.firstSnapshot = snapshot;
			this.period = period;
		}
    	
    	public void update(InstanceUsageSnapshot snapshot)
    	{
    		this.lastSnapshot = snapshot;
    	}

    	public InstanceAttributes getInstanceAttributes()
    	{
    		return this.insAttrs;
    	}
    	
    	public long getDurationSecs()
    	{
    		long truncatedBeginMs = Math.max(period.getBeginningMs(), firstSnapshot.getTimestampMs());
    		long truncatedEndMs   = Math.min(period.getEndingMs(), lastSnapshot.getTimestampMs());
    		return ( truncatedEndMs-truncatedBeginMs ) / 1000;
    	}
    	
    	public Period getDurationPeriod()
    	{
    		long truncatedBeginMs = Math.max(period.getBeginningMs(), firstSnapshot.getTimestampMs());
    		long truncatedEndMs   = Math.min(period.getEndingMs(), lastSnapshot.getTimestampMs());
    		return new Period(truncatedBeginMs, truncatedEndMs);
    	}
    	
    	public long getDiskIoMegs()
    	{
			double duration = (double)(period.getEndingMs()-period.getBeginningMs());
			double gap = 0d;
    		double result =
    			(double)lastSnapshot.getCumulativeDiskIoMegs() -
    			(double)firstSnapshot.getCumulativeDiskIoMegs();
			/* Extrapolate fractional usage for snapshots which occurred
			 * before report beginning or after report end.
			 */
    		if (firstSnapshot.getTimestampMs() < period.getBeginningMs()) {
    			gap = (double)(period.getBeginningMs()-firstSnapshot.getTimestampMs());
    			result *= 1d-(gap/duration);
    		}
    		if (lastSnapshot.getTimestampMs() > period.getEndingMs()) {
    			gap = (double)(lastSnapshot.getTimestampMs()-period.getEndingMs());
    			result *= 1d-(gap/duration);
    		}
    		return (long) result;
    	}
    	
    	public long getNetIoMegs()
    	{
			double duration = (double)(period.getEndingMs()-period.getBeginningMs());
			double gap = 0d;
    		double result =
    			(double)lastSnapshot.getCumulativeNetworkIoMegs() -
    			(double)firstSnapshot.getCumulativeNetworkIoMegs();
			/* Extrapolate fractional usage for snapshots which occurred
			 * before report beginning or after report end.
			 */
    		if (firstSnapshot.getTimestampMs() < period.getBeginningMs()) {
    			gap = (double)(period.getBeginningMs()-firstSnapshot.getTimestampMs());
    			result *= 1d-(gap/duration);
    		}
    		if (lastSnapshot.getTimestampMs() > period.getEndingMs()) {
    			gap = (double)(lastSnapshot.getTimestampMs()-period.getEndingMs());
    			result *= 1d-(gap/duration);
    		}
    		return (long) result;
    	}

    }


}
