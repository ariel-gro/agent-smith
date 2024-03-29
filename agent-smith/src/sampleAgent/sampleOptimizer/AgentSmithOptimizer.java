package sampleAgent.sampleOptimizer;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import trainer.GameLogDataStruct;

import edu.umich.eecs.tac.props.BidBundle;
import edu.umich.eecs.tac.props.Query;
import edu.umich.eecs.tac.props.QueryReport;
import edu.umich.eecs.tac.props.SalesReport;
import arch.Optimizer;
import arch.IEstimator.QueryEstimateResult;

public class AgentSmithOptimizer extends Optimizer
{
	private static final Logger log = Logger.getLogger("optimizer");

	/*
	 * Game Constants
	 */
	final static protected double UNIT_SALES_PROFIT = 10;
	final static protected double MANUFACTURER_SPECIALIST_BONUS = 0.4;
	final static protected double COMPONENT_SPECIALIST_BONUS = 0.6;
	
	
	/*
	 * algorithm parameters
	 */
	protected double INITIAL_BID_DEFAULT = 1.5;

	protected double initialDailyQuerySpentLimit = 200;
	
	protected int dailyQuerySpentLimit = 300;
	
	protected float incRoiFactor = 1.3f;	
	
	protected float incBidFactor = 0.1f;
	
	protected float incBidFactorMediumPriority = 0.5f;
	
	protected float incBidFactorHighPriority = 0.6f;

	protected float startTargetRoi = 10.0f;
	
	protected float spentLimitMediumPriorityFactor = 1.0f;
		
	protected float spentLimitHighPriorityFactor = 1.1f;
	
	protected float extendCapacityFactor = 1.0f;
	
	protected double campaignDailySpendLimitFactor = 12.0;
	
	protected double dailyQuerySpentLimitOnBurstFactor = 1.2;
		
	protected float burstBidIncFactor = 1.2f;
	
	protected float burstIdentifyFactor = 1.4f;
	
	protected int positionThreshold = 4;
	
	/*
	 * quota calculation variables
	 */
	/** total capacity for capacity window */
	protected int CAPACITY; 		
	
	/** number of days in capacity window */
	int CAPACITY_WINDOW; 	
	
	/**holds all sales for capacity window duration*/
	protected Queue<Integer> salesWindow;
	
	/** capacity used for capacity window */
	protected int capacityUsed; 	
	
	/** estimated capacity remaining for capacity window */
	protected int capacityQuota;  	
		
	protected int estimatedSales;
	
	/*
	 * holds information for all relevant queries
	 */
	/** Holds all the optimized queries bids */
	protected List<AgentSmithOptimizerQuery> querySpace;
	
	protected QueryEstimateResult estimated;

	protected BidBundle bidBundle;
	
	protected Set<Query> querySet;

	protected int day;
	
	protected String componentSpecialty;
	
	protected String manufacturerSpecialty;
	
	protected EquateROI equateRoiInstance;
	
	protected boolean useEquateRoiAlgorithm = false;

	public AgentSmithOptimizer()
	{
		querySpace = new LinkedList<AgentSmithOptimizerQuery>();
		salesWindow = new LinkedList<Integer>();
		estimated = new QueryEstimateResult();
	}

	/*
	 * @see arch.IAgentComponent#simulationReady()
	 */
	public void simulationReady()
	{
		querySet = aaAgent.getQuerySet();
		day = 0;
		int numOfGames = GameLogDataStruct.getInstance().getGamesReports().size();
		if (numOfGames % 2 != 0){
			useEquateRoiAlgorithm = false;
			System.out.println("not using useEquateRoiAlgorithm");
		}else{
			useEquateRoiAlgorithm = true;
			System.out.println("using useEquateRoiAlgorithm");
		}
		
		equateRoiInstance = new EquateROI(startTargetRoi, incRoiFactor, incBidFactor, incBidFactorMediumPriority, incBidFactorHighPriority, positionThreshold, burstBidIncFactor, burstIdentifyFactor);
		
		/* create queries space */
		for (Query query : querySet)
		{
			querySpace.add(new AgentSmithOptimizerQuery(query));
		}

		// we populate the bidBundle once upon simulationReady and update upon optimize()
		bidBundle = new BidBundle();
		for (AgentSmithOptimizerQuery optquery : querySpace)
		{
			bidBundle.addQuery(optquery.getQuery(), INITIAL_BID_DEFAULT, optquery.getAd());
			bidBundle.setDailyLimit(optquery.getQuery(), Double.NaN);
			bidBundle.setCampaignDailySpendLimit(Double.NaN);
			
			optquery.setBestBidIndex((int)(INITIAL_BID_DEFAULT/optquery.RESOULUTION_OF_BIDS));
			optquery.setEquateRoiBid(INITIAL_BID_DEFAULT);
			optquery.nextDay(0);
		}

		CAPACITY = (int) (aaAgent.getAdvertiserInfo().getDistributionCapacity() * extendCapacityFactor);
		CAPACITY_WINDOW = aaAgent.getAdvertiserInfo().getDistributionWindow();
		capacityQuota = 0;
		capacityUsed = 0;
		
		componentSpecialty = aaAgent.getAdvertiserInfo().getComponentSpecialty();
		manufacturerSpecialty = aaAgent.getAdvertiserInfo().getManufacturerSpecialty();
		
		log.log(Level.FINE, "Capacity: " + CAPACITY);
	}

	public void nextDay(int d)
	{
		day = d;
		for (AgentSmithOptimizerQuery query : querySpace)
		{
			query.nextDay(d);
		}
	}

	/*
	 * merely fetch the optimized info for each query (as calculated upon
	 * reception of the the status messages) and update the return bidBundle
	 * accordingly
	 * 
	 * @see arch.IOptimizer#optimize()
	 */
	public BidBundle optimize()
	{
		Query query;
		int tomorrow = day + 1;
		double dailyQuerySpentLimit;

		log.log(Level.FINE, " Bidding for day " + tomorrow);
		for (AgentSmithOptimizerQuery optquery : querySpace)
		{
			query = optquery.getQuery();
			
			boolean isPriorityManufacturer = manufacturerSpecialty.equalsIgnoreCase(query.getManufacturer());
			boolean isPriorityComponent = componentSpecialty.equalsIgnoreCase(query.getComponent());
			
			/* dailyLimit is irrelevant until day 2 since no reports were received */
			if (tomorrow > 2)
			{	
				dailyQuerySpentLimit = (isPriorityManufacturer||isPriorityComponent)?((isPriorityManufacturer&&isPriorityComponent)?(optquery.dailyLimit*spentLimitHighPriorityFactor):(optquery.dailyLimit*spentLimitMediumPriorityFactor)):optquery.dailyLimit;
			}
			else
			{
				optquery.setDailyLimit(initialDailyQuerySpentLimit);
				dailyQuerySpentLimit = (isPriorityManufacturer||isPriorityComponent)?((isPriorityManufacturer&&isPriorityComponent)?(initialDailyQuerySpentLimit*spentLimitHighPriorityFactor):(initialDailyQuerySpentLimit*spentLimitMediumPriorityFactor)):initialDailyQuerySpentLimit;
			}
			
			if(useEquateRoiAlgorithm)
			{
				bidBundle.setBid(query, optquery.equateRoiBid);
				GameLogDataStruct.getInstance().addLastBid(query, optquery.equateRoiBid);
			}
			else
			{
				bidBundle.setBid(query, optquery.bids[optquery.bestBidIndex]);
				GameLogDataStruct.getInstance().addLastBid(query, optquery.bids[optquery.bestBidIndex]);
			}
			
			bidBundle.setAd(query, optquery.ad);
			bidBundle.setDailyLimit(query, dailyQuerySpentLimit);
			
			//bidBundle.setCampaignDailySpendLimit(dailyQuerySpentLimit*campaignDailySpendLimitFactor);

			// for log prints
			Double dlimit = dailyQuerySpentLimit;
			double dbid = (double) (((Double) (100 * bidBundle.getBid(query))).intValue()) / 100;
			log.log(Level.FINE, "(" + query.getManufacturer() + "," + query.getComponent() + ")" + " bid: " + dbid + ", dailyLimit: " + dlimit.intValue());
		}

		bidBundle.setCampaignDailySpendLimit(Double.NaN);

		return bidBundle;
	}

	/**
	 * the quota of items to sell tomorrow (tday) is determined based on past
	 * sales (including yesterday sales - yesterday).
	 */
	public void handleSalesReport(SalesReport salesReport, int yesterday)
	{
		int totalYesterdaySales = 0;
		int estimatedCapacityUsed = 0;
		int today = yesterday + 1;
		int tomorrow = yesterday + 2;
		
		log.log(Level.FINE, " Sales report for day " + yesterday);

		if (yesterday > 0)
		{ /* sales reports are not expected for day 0 */

			for (AgentSmithOptimizerQuery optquery : querySpace)
			{
				optquery.yesterdayConversions = salesReport.getConversions(optquery.getQuery());
				optquery.yesterdayRevenue = salesReport.getRevenue(optquery.getQuery());

				log.log(Level.FINE, "(" + optquery.getQuery().getManufacturer() + "," + optquery.getQuery().getComponent() + ")" + " day " + yesterday + " reported sales: " + optquery.yesterdayConversions + ", revenue: " + optquery.yesterdayRevenue);

				totalYesterdaySales += optquery.yesterdayConversions;
			}

			// update capacity used form capacity window and keep sales of last (capacityWindow - 1) days */
			capacityUsed = capacityUsed + totalYesterdaySales;
			salesWindow.add(totalYesterdaySales);
			if (salesWindow.size() > CAPACITY_WINDOW - 1)
			{
				capacityUsed = capacityUsed - salesWindow.poll();
			}

			//today's sales estimation is the average of the Estimator's estimation and the quota allocated yesterday
			estimatedSales = (estimatedSales + capacityQuota) / 2; //NEED TO EVALUATE THIS !!!

			//if late enough, deduct oldest day in capacity window from capacity usage estimation
			if (salesWindow.size() >= CAPACITY_WINDOW - 1)
			{
				estimatedCapacityUsed = capacityUsed + estimatedSales - salesWindow.peek();
				capacityQuota = (CAPACITY - capacityUsed)/2;
			} else
			{
				estimatedCapacityUsed = capacityUsed + estimatedSales;
				capacityQuota = (CAPACITY - capacityUsed)/(CAPACITY_WINDOW - yesterday);
			}		
		}

		log.log(Level.FINE, "Day " + yesterday + " sales: " + totalYesterdaySales + ", Day " + today + " sales estimate: " + estimatedSales);
		log.log(Level.FINE, "Day " + today + " estimated capacity used: " + estimatedCapacityUsed + "  Day " + tomorrow + " capacity quota: " + capacityQuota);
	}

	public void handleQueryReport(QueryReport queryReport, int yday)
	{
		//reset the estimation for tomorrow's sales - we need this figure for the quota calculations
		estimatedSales = 0;
		log.log(Level.FINE, " Query report for day " + yday);
		
		//long start = System.currentTimeMillis();
		if (yday > 0)
		{
			//System.out.println("***************** Before HybridPenalizedGreedyMCKP, capacity=" + CAPACITY + " window=" + CAPACITY_WINDOW);
			GreedyMCKP gMkcp = new GreedyMCKP(capacityQuota, querySet);
			equateRoiInstance.clear();
			
			//first we score each query and ask the estimator for tomorrow's estimates
			for (AgentSmithOptimizerQuery query : querySpace)
			{
				boolean isPriorityManufacturer = manufacturerSpecialty.equalsIgnoreCase(query.getQuery().getManufacturer());
				boolean isPriorityComponent = componentSpecialty.equalsIgnoreCase(query.getQuery().getComponent());
				
				query.yesterdayCost = queryReport.getCost(query.getQuery());		
				query.yesterdayClicks = queryReport.getClicks(query.getQuery());
				query.yesterdayPosition = queryReport.getPosition(query.getQuery());
				query.yesterdayImpressions = queryReport.getImpressions(query.getQuery());
				
				for (int bidIndex = 0; bidIndex < query.bids.length; bidIndex++)
				{
					estimated = aaEstimator.estimateQuery(query.getQuery(), query.bids[bidIndex], query.ad, query.dailyLimit, yday + 2);		
					System.out.println("OPTIMIZER: Bid-" + query.bids[bidIndex] + ", Impr-" + estimated.getImpressions() + ", CPC-" + estimated.getCpc() + ", Conv-" +  estimated.getConversions() + ", Clicks-" + estimated.getClicks() + ", Prof-" +  estimated.getProfits());
					query.setEstimates(bidIndex, estimated.getImpressions(), estimated.getCpc(), estimated.getConversions(), estimated.getClicks(), estimated.getProfits());
					gMkcp.add(query.getQuery(), bidIndex, query.bids[bidIndex], estimated.getConversions(), estimated.getProfits());
				}
				
				equateRoiInstance.add(query.getQuery(), query.yesterdayConversions, query.yesterdayClicks, isPriorityManufacturer, isPriorityComponent, query.yesterdayPosition, query.yesterdayImpressions, yday);
			}

			//finally, we can calculate bid and spend limits for the bid based on allocated units
			Map<Query, Integer> bestBidsIndexMap = gMkcp.calcSolution();
		
			Map<Query, Double> queriesBidsMap = equateRoiInstance.calcBids(capacityQuota);
			
			for (AgentSmithOptimizerQuery query : querySpace)
			{		
				if(useEquateRoiAlgorithm)
				{
					query.setEquateRoiBid(queriesBidsMap.get(query.getQuery()));
				
					if(equateRoiInstance.getIsBurst())
						query.setDailyLimit(dailyQuerySpentLimit*dailyQuerySpentLimitOnBurstFactor); // NEED TO ADJUST THIS	
					else
						query.setDailyLimit(dailyQuerySpentLimit); // NEED TO ADJUST THIS			
					
					estimatedSales += capacityQuota/querySpace.size();
				}
				else
				{
					query.setBestBidIndex(bestBidsIndexMap.get(query.getQuery()));	
					//query.calculateDailyLimit(bestBidsIndexMap.get(query.getQuery()));
					//estimatedSales += (int) (query.estConversions[query.bestBidIndex]);
					
					query.setDailyLimit(dailyQuerySpentLimit); // NEED TO ADJUST THIS			
					
					estimatedSales += capacityQuota/querySpace.size();
				}		
				
				double dcpc = (double) (((Double) (100 * queryReport.getCPC(query.getQuery()))).intValue()) / 100;
				log.log(Level.FINE,
						"(" + query.getQuery().getManufacturer() + "," + query.getQuery().getComponent() + ")" + " day " + yday + " reported " + " impressions:"
								+ queryReport.getImpressions(query.getQuery()) + " clicks:" + queryReport.getClicks(query.getQuery()) + " cv:" + query.yesterdayConversions + " cpc:" + dcpc);
		
			}
		}
		//long stop = System.currentTimeMillis();
		//System.out.println("***** Total run time for optimizer (in milis) = " + (stop-start));
	}

	public void simulationFinished()
	{
		querySpace.clear();
		salesWindow.clear();
	}

	public void simulationSetup()
	{
		//initialBidIndex = aaConfig.getPropertyAsInt("Bid", INITIAL_BID_INDEX_DEFAULT);
		//initialDailyQuerySpentLimit = aaConfig.getPropertyAsDouble("Limit", INITIAL_DAILY_QUERY_SPENT_LIMIT_DEFAULT);
		
	//	incRoiFactor = aaConfig.getPropertyAsFloat("equateRoiIncRoiFactor", EQUATE_ROI_INCREASE_ROI_FACTOR_DEFAULT);
	//	incBidFactor = aaConfig.getPropertyAsFloat("equateRoiIncBidFactor", EQUATE_ROI_INCREASE_BID_FACTOR_DEFAULT);
	//	incBidFactorPriority = aaConfig.getPropertyAsFloat("equateRoiIncBidFactorPriority", EQUATE_ROI_INCREASE_PRIORITY_BID_FACTOR_DEFAULT);
	}

}
