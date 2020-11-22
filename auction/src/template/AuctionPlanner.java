package template;

//the list of imports
import java.io.File;
import java.util.*;

import logist.LogistSettings;
import logist.Measures;
import logist.behavior.AuctionBehavior;
import logist.agent.Agent;
import logist.config.Parsers;
import logist.simulation.Vehicle;
import logist.plan.Plan;
import logist.task.Task;
import logist.task.TaskDistribution;
import logist.task.TaskSet;
import logist.topology.Topology;
import logist.topology.Topology.City;
import logist.task.DefaultTaskDistribution;

/**
 * A very simple auction agent that assigns all tasks to its first vehicle and
 * handles them sequentially.
 * 
 */
@SuppressWarnings("unused")
public class AuctionPlanner implements AuctionBehavior {

	private Topology topology;
	private DefaultTaskDistribution distribution;
	private Agent agent;
	private Random random;
	private Vehicle vehicle;
	private City currentCity;
	private long timeout_plan;
	private long timeout_setup;
	private long timeout_bid;
	private Planner auctionPlanner;
	private Planner opponentPlanner;
	private Integer numAuctions;
	private Long winningBids;
	private List<Long> opponentBids;
	private List<Long> ownBids;
	private List<Double> opponentMarginalCosts;
	private List<Double> ownMarginalCosts;
	private List<Long> historicalBids;
	private boolean trickOpponent;
	private List<Double> estimateOpponentBids;
	private boolean future;
	private double shortestRouteCost;

	@Override

	public void setup(Topology topology, TaskDistribution distribution,
			Agent agent) {

		this.topology = topology;
		this.distribution = (DefaultTaskDistribution) distribution;
		this.agent = agent;
		this.vehicle = agent.vehicles().get(0);
		this.currentCity = vehicle.homeCity();
		this.auctionPlanner = new Planner(agent.vehicles());
		this.numAuctions = 0;
		//Suppose the opponent has same vehicles
		this.opponentPlanner = new Planner(agent.vehicles());
		long seed = -9019554669489983951L * currentCity.hashCode() * agent.id();
		this.random = new Random(seed);
		this.winningBids = (long) 0;
		this.opponentBids = new ArrayList<>();
		this.opponentMarginalCosts = new ArrayList<>();
		this.ownMarginalCosts = new ArrayList<>();
		this.ownBids = new ArrayList<>();
		this.historicalBids = new ArrayList<>();
		this.trickOpponent = true;
		this.estimateOpponentBids = new ArrayList<>();
		this.future = false;

		LogistSettings ls = null;
		try {
			ls = Parsers.parseSettings("config" + File.separator + "settings_auction.xml");
		}
		catch (Exception exc) {
			System.out.println("There was a problem loading the configuration file.");
		}

		timeout_plan = ls.get(LogistSettings.TimeoutKey.PLAN);
		timeout_setup = ls.get(LogistSettings.TimeoutKey.SETUP);
		timeout_bid = ls.get(LogistSettings.TimeoutKey.BID);

		double average = 0;
		int count = 0;
		for (City c1 : topology) {
			for (City c2 : topology) {
				if (!c1.name.equals(c2.name) && c1.hasNeighbor(c2)) {
					average += c1.distanceTo(c2);
					count++;
				}
			}
		}
		average/=count;
		shortestRouteCost = average;

	}

	public long listSum(Long[] longList){
		long sum = 0;
		for (long i : longList)
			sum = sum + i;
		return sum;
	}

	public long listSum(List<Long> longList){
		long sum = 0;
		for (long i : longList)
			sum = sum + i;
		return sum;
	}

	@Override
	public void auctionResult(Task previous, int winner, Long[] bids) {
		/**
		 * This signal informs the agent about the outcome of an auction.
		 * lastWinner is the id of the agent that won the task.
		 * The actual bids of all agents is given as an array lastOffers indexed by agent id.
		 * A null offer indicates that the agent did not participate in the auction.
		 */

		// check bid size and verify
		if (bids.length == 2){
			this.opponentBids.add((listSum(bids)-bids[this.agent.id()]));
		}
		else if (bids.length > 2){
			System.out.println("We have only optimized our solution for 2 companies. But "+bids.length+" bids found.");
			this.opponentBids.add((listSum(bids)-bids[this.agent.id()])/(bids.length-1));
		}
		else{
			System.out.println("No other bids were found. We can exploit the auction.");
			System.exit(1);
		}
		this.ownBids.add(bids[this.agent.id()]);
		this.historicalBids.add(bids[winner]);

		if (winner == this.agent.id()) {

			System.out.println("You have won task "+ previous.id);
			win(this.auctionPlanner, previous);
			this.winningBids += bids[this.agent.id()];

		} else {
			//update plan of opponent
			System.out.println("Your opponent has won task "+ previous.id);
			win(this.opponentPlanner, previous);

		}



	}
	
	@Override
	public Long askPrice(Task task) {
		/**
		 * Asks the agent to offer a price for a task and it is sent for each task that is auctioned.
		 * The agent should return the amount of money it would like to receive for delivering that task.
		 * If the agent wins the auction, it is assigned the task, and it must deliver it in the final plan.
		 * The reward of the task will be set to the agent’s price.
		 * It is possible to return null to reject the task unconditionally.
		 */
		System.out.println("Task " + task.id + " is auctioned.");
		if (this.vehicle.capacity() < task.weight)
			return null;
		this.numAuctions++;
		//bidding parameters
		int minAuctions = 5;
		int numPredictions = numAuctions <= minAuctions ? minAuctions - numAuctions : 1;
		double epsilon = 3*(double) listSum(historicalBids)/(numAuctions*10); // this is roughly 10% (or lower) of the realised bids (almost 10% when number of tasks increased.)
//		double epsilon = shortestRouteCost;

		double minProfit = epsilon;
		double profitMarkup = 1.00; // how much more you want at margin cost

		double maxEstimateRatio = 2.0; // max cut-off for opponents cost multiplication
		int maxLookBackForEstimateRation = numAuctions-1; // how many entries to be used to calculate estimate ratio
		double bid;

		System.out.println("Computing opponent marginal cost with task "+task.id);

		//compute marginal opponent cost
		double marginalOpponentCost = computeMarginalCost(this.opponentPlanner, task);
		this.opponentMarginalCosts.add(marginalOpponentCost);

		//estimate opponent's strategy: guess his ratio with history
//		double estimateRatio = estimateRatio(this.opponentBids,this.opponentMarginalCosts);
		double estimateRatio = estimateRatioWithLimitedLookBack(this.opponentBids,this.opponentMarginalCosts, maxLookBackForEstimateRation);

		// the range of estimateRatio should be withing 0 and maxEstimateRatio
		//estimateRatio = Math.max( estimateRatio, 0);
		estimateRatio = Math.min( estimateRatio, maxEstimateRatio);

		double opponentBid = estimateRatio*marginalOpponentCost;
		this.estimateOpponentBids.add(opponentBid);
		System.out.println("Computing own marginal cost with task "+task.id);

		//compute own marginal cost
		double marginalCost = computeMarginalCost(this.auctionPlanner, task);
		this.ownMarginalCosts.add(marginalCost);

		// if opponents marginal cost is 0 and ours is not, try to trick the opponent by giving a spike to bids
		if ((marginalOpponentCost == 0) && trickOpponent && (marginalCost!=0)) {
			trickOpponent = false;
			return (long) Double.POSITIVE_INFINITY;
		}

		if (marginalCost == 0) {
			return (long) epsilon;
		}

		if (future) {
			double expectedFutureCost = computeExpectedCost(numPredictions, marginalCost);
			marginalCost = Math.min(marginalCost, expectedFutureCost);
		}

		bid = Math.max((opponentBid - epsilon), (marginalCost* profitMarkup));


		return (long) Math.max(Math.floor(bid) , minProfit); // we should not bid less than minProfit
	}

	@Override
	public List<Plan> plan(List<Vehicle> vehicles, TaskSet tasks) {

		debriefing();
		long time_start = System.currentTimeMillis();

		System.out.println("Computing plan.");
		List<Plan> plans = buildPlan(this.auctionPlanner.getBestSolution(), vehicles);

		long time_end = System.currentTimeMillis();
		long duration = time_end - time_start;
		System.out.println("The plan was generated in " + duration + " milliseconds.");

		return plans;
	}

	public static List<Plan> buildPlan(Solution s, List<Vehicle> vehicles) {

		List <Plan> plans = new ArrayList<>();

		for (Vehicle v : vehicles) {
			// create the plan for each vehicle
			Plan plan = new Plan(v.homeCity());
			if (s != null) {
				// first go to pickup city of first task
				TaskAnnotated current = s.nextTask(v);
				if (current != null) {
					List<City> path = v.homeCity().pathTo(current.getTask().pickupCity);
					for (City c : path) {
						plan.appendMove(c);
					}
					plan.appendPickup(current.getTask());
					TaskAnnotated nextA = s.nextTask(current);
					while (nextA != null) {
						if (current.getActivity() == Planner.Activity.Pick) {
							if (nextA.getActivity() == Planner.Activity.Pick) {
								path = current.getTask().pickupCity.pathTo(nextA.getTask().pickupCity);
								for (City c : path) {
									plan.appendMove(c);
								}
								plan.appendPickup(nextA.getTask());
							} else {
								path = current.getTask().pickupCity.pathTo(nextA.getTask().deliveryCity);
								for (City c : path) {
									plan.appendMove(c);
								}
								plan.appendDelivery(nextA.getTask());
							}
						} else {
							if (nextA.getActivity() == Planner.Activity.Pick) {
								path = current.getTask().deliveryCity.pathTo(nextA.getTask().pickupCity);
								for (City c : path) {
									plan.appendMove(c);
								}
								plan.appendPickup(nextA.getTask());
							} else {
								path = current.getTask().deliveryCity.pathTo(nextA.getTask().deliveryCity);
								for (City c : path) {
									plan.appendMove(c);
								}
								plan.appendDelivery(nextA.getTask());
							}
						}
						current = nextA;
						nextA = s.nextTask(nextA);
					}
				}
			}
			plans.add(plan);
		}
		return plans;
	}

	private Plan naivePlan(Vehicle vehicle, TaskSet tasks) {
		City current = vehicle.getCurrentCity();
		Plan plan = new Plan(current);

		for (Task task : tasks) {
			// move: current city => pickup location
			for (City city : current.pathTo(task.pickupCity))
				plan.appendMove(city);

			plan.appendPickup(task);

			// move: pickup location => delivery location
			for (City city : task.path())
				plan.appendMove(city);

			plan.appendDelivery(task);

			// set current city
			current = task.deliveryCity;
		}
		return plan;
	}

	double computeMarginalCost(Planner plan, Task t) {

		Planner plannerWithTask = new Planner(plan.vehicles, plan.tasks);
		plannerWithTask.addTask(t);

		Solution formerSolution = plan.getBestSolution();

		if (formerSolution != null) {
			Solution init = new Solution(formerSolution);
			init.insertTask(t);
			plannerWithTask.search(init);
		} else {
			plannerWithTask.search(plannerWithTask.selectInitialSolution());
		}

		return Math.max(0, plannerWithTask.getBestCost() - plan.getBestCost());
	}

	void win(Planner plan, Task task) {

		plan.addTask(task);
		Solution formerSolution = plan.getBestSolution();
		if (formerSolution != null) {
			Solution init = new Solution(formerSolution);
			init.insertTask(task);
			plan.search(init);
		} else {
			plan.search(plan.selectInitialSolution());
		}

	}

	double estimateRatio(List<Long> bids, List<Double> margCosts) {
		double r = 0;
		if (bids.isEmpty() || margCosts.isEmpty()) {
			return 1;
		} else {
			for (int i = 0; i < bids.size(); ++i) {
				if (margCosts.get(i) > 0) {
					r += (double) bids.get(i)/margCosts.get(i);
				}
			}
		}
		return r/bids.size();
	}

	double estimateRatioWithLimitedLookBack(List<Long> bids, List<Double> margCosts, int maxLookBack) {
		int lookBacks = 0;
		double r = 0;
		if (bids.isEmpty() || margCosts.isEmpty()) {
			return 1;
		} else {
			for (int i = bids.size()-1; i >= 0; --i) {
				lookBacks++;
				if (margCosts.get(i) > 0) {
					r += (double) bids.get(i)/margCosts.get(i);
				} else if (bids.get(i)>0 && margCosts.get(i) == 0) {
					r += 1;
				}
				if (maxLookBack <= lookBacks){
					return r/maxLookBack;
				}
			}
		}
		return r/bids.size();
	}

	double computeExpectedCost(int numPredictions, double margCost) {

		double expectedCost = margCost;

		Planner futurePlan = new Planner(auctionPlanner.vehicles, auctionPlanner.tasks);
		Solution init = auctionPlanner.getBestSolution();

		double formerCost = margCost;

		//Solution init;

		for (int i = 0; i < numPredictions; ++i) {
			Task rTask = distribution.createTask();
			if (init != null) {
				init = new Solution(init);
				futurePlan.addTask(rTask);
				init.insertTask(rTask);
				futurePlan.search(init);
			} else {
				futurePlan.addTask(rTask);
				futurePlan.search(futurePlan.selectInitialSolution());
				init = new Solution(futurePlan.getBestSolution());
			}

			expectedCost += futurePlan.getBestCost() - formerCost;
			formerCost = futurePlan.getBestCost();
		}

		return Math.max(0, expectedCost/(numPredictions+1));
	}

	public void debriefing() {
		System.out.println("Bid history:");
		System.out.println("My bids" + this.ownBids);
		System.out.println("My marg costs "+ this.ownMarginalCosts );
		System.out.println("Opponent bids" + this.opponentBids);
		System.out.println("Opponent marg costs" + this.opponentMarginalCosts);
		System.out.println("Losses:");
		for (int i = 0; i < ownBids.size();++i) {
			if (ownBids.get(i) > opponentBids.get(i)) {
				System.out.println("Task " + i);
				System.out.println("My bid " + ownBids.get(i));
				System.out.println("Opponent bid " + opponentBids.get(i));
				System.out.println("Estimate Opponent bid " + estimateOpponentBids.get(i));
				System.out.println("My marg cost " + ownMarginalCosts.get(i));
				System.out.println("Opponent marg cost " + opponentMarginalCosts.get(i));
			}
		}
		System.out.println("The total-cost is " + this.auctionPlanner.getBestCost());
		System.out.println("The total-wining score is " + this.winningBids);
		System.out.println("You have obtained " + auctionPlanner.getTasks().size() + " out of " + this.numAuctions + " auctions.");
		System.out.println("with a benefit of :" + (this.winningBids - this.auctionPlanner.bestCost));
	}
}
