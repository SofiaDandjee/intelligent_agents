package template;

//the list of imports
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

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
	private boolean recomputed;
	private Planner auctionPlanner;
	private Planner opponentPlanner;
	private Integer numAuctions;
	private Long winningBids;
	private List<Long> opponentBids;
	private List<Long> ownBids;
	private List<Double> opponentMarginalCosts;
	private List<Double> ownMarginalCosts;
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
	}

	@Override
	public void auctionResult(Task previous, int winner, Long[] bids) {
		/**
		 * This signal informs the agent about the outcome of an auction.
		 * lastWinner is the id of the agent that won the task.
		 * The actual bids of all agents is given as an array lastOffers indexed by agent id.
		 * A null offer indicates that the agent did not participate in the auction.
		 */
		opponentBids.add(bids[1]);
		ownBids.add(bids[0]);
		if (winner == agent.id()) {

			System.out.println("You have won task "+ previous.id);
//			currentCity = previous.deliveryCity;
			//update own plan
			win(auctionPlanner, previous);
			winningBids += bids[agent.id()];

		} else {
			//update plan of opponent
			System.out.println("Your opponent has won task "+ previous.id);
			win(opponentPlanner, previous);
//			System.out.print("Estimate opponent plan :");
//			opponentPlanner.getBestSolution().print();

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
		if (vehicle.capacity() < task.weight)
			return null;

		numAuctions+=1;
		//bidding parameters
		int conservativeAuctions = 5;
		int numPredictions = 1;

		System.out.println("Computing opponent marginal cost with task "+task.id);

		//compute marginal opponent cost
		double marginalOpponentCost = computeMarginalCost(opponentPlanner, task);
		opponentMarginalCosts.add(marginalOpponentCost);

		//estimate opponent's strategy: guess his ratio with history
		double opponentBid = estimateRatio(opponentBids,opponentMarginalCosts)*marginalOpponentCost;

		System.out.println("Computing own marginal cost with task "+task.id);

		//compute own marginal cost
		double marginalCost = computeMarginalCost(auctionPlanner, task);
		ownMarginalCosts.add(marginalCost);

		double epsilon = 1;

		//bid slightly lower than opponent
		double finalMarginalCost = opponentBid - epsilon;


		//TO DO: take into account probability distributions of tasks

		double bid;
		double ratio;
		// bid less at first rounds
		if (numAuctions <=conservativeAuctions) {
			ratio = (double) numAuctions/conservativeAuctions;
		} else {
			//how to fix this parameter?
			ratio = 1.05;
		}


		//if marginal cost or opponent cost is 0, bid very low
		if (marginalCost == 0 || marginalOpponentCost == 0) {
			bid = epsilon;
		} else {
			bid = ratio * finalMarginalCost;
		}

		return (long) Math.floor(bid);
	}

	@Override
	public List<Plan> plan(List<Vehicle> vehicles, TaskSet tasks) {

		System.out.println("Bid history:");
		System.out.println("My bids" + ownBids);
		System.out.println("My marg costs "+ ownMarginalCosts );
		System.out.println("Opponent bids" + opponentBids);
		System.out.println("Opponent marg costs" + opponentMarginalCosts);

		System.out.println("Computing plan.");

		long time_start = System.currentTimeMillis();

		System.out.println("The total-cost is " + auctionPlanner.getBestCost());
		System.out.println("The total-wining score is " + winningBids);
		System.out.println("You have obtained " + tasks.size() + " out of " + numAuctions + " auctions.");
		System.out.println("with a benefit of :" + (winningBids - auctionPlanner.bestCost));
		List<Plan> plans = buildPlan(auctionPlanner.getBestSolution(), vehicles);

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
					if (current.getActivity() == Planner.Activity.Pick){
						if (nextA.getActivity() == Planner.Activity.Pick){
							path = current.getTask().pickupCity.pathTo(nextA.getTask().pickupCity);
							for (City c : path) {
								plan.appendMove(c);
							}
							plan.appendPickup(nextA.getTask());
						}
						else{
							path = current.getTask().pickupCity.pathTo(nextA.getTask().deliveryCity);
							for (City c : path) {
								plan.appendMove(c);
							}
							plan.appendDelivery(nextA.getTask());
						}
					}
					else{
						if (nextA.getActivity() == Planner.Activity.Pick){
							path = current.getTask().deliveryCity.pathTo(nextA.getTask().pickupCity);
							for (City c : path) {
								plan.appendMove(c);
							}
							plan.appendPickup(nextA.getTask());
						}
						else{
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

	double computeFutureCosts(int numPredictions, Task t) {

		Solution formerSolution = new Solution(auctionPlanner.getBestSolution());
		List<Task> previous = new ArrayList<>(auctionPlanner.tasks);
		for (int i = 0; i < numPredictions; ++i) {
			previous.add(distribution.createTask());
			formerSolution.insertTask(t);
		}
		Planner futurePlanner = new Planner(auctionPlanner.vehicles, previous);
		futurePlanner.search(formerSolution);
		return Math.max(0, futurePlanner.getBestCost() - auctionPlanner.getBestCost());
	}
}