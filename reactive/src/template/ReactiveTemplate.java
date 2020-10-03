package template;

import java.util.*;

import logist.simulation.Vehicle;
import logist.agent.Agent;
import logist.behavior.ReactiveBehavior;
import logist.plan.Action;
import logist.plan.Action.Move;
import logist.plan.Action.Pickup;
import logist.task.Task;
import logist.task.TaskDistribution;
import logist.topology.Topology;
import logist.topology.Topology.City;

public class ReactiveTemplate implements ReactiveBehavior {

	private Random random;
	private double pPickup;
	private int numActions;
	private Agent myAgent;
	private List<State> states;
	private List<Act> actions;

	class Act {
		//An action is either pickup or move, to a destination, with a reward
		boolean pickUp;
		double reward;
		private City destination;

		Act(City d, Boolean pu, double r) {
			pickUp = pu;
			reward = r;
			destination = d;
		}

		public void print() {
			if (pickUp) {
				System.out.print("Pick up");
			} else {
				System.out.print("Move to");
			}
			System.out.println(" to " + destination);
			System.out.println("With reward ");
			System.out.println(reward);
		}

		public City getDestination() {
			return destination;
		}

		boolean pickUp() {
			return pickUp;
		}

		void setReward(double r) {
			reward = r;
		}
	}

	class State {
		private City location;
		private City destination;
		private Boolean task;

		public List<Act> actions;

		void addAction(Act act) {
			actions.add(act);
		}

		Act getBestAction() {
			double score = Double.NEGATIVE_INFINITY;
			Act best = null;
			for (Act action : actions) {
				if (action.reward >= score) {
					best = action;
					score = best.reward;
				}
			}
			return best;
		}

		public void printState() {
			System.out.println("At " + location );
			if (task) {
				System.out.println("With task to " + destination);
			} else {
				System.out.println("With no task");
			}
			System.out.println("Best action is ");
			getBestAction().print();
		}
		public State(City c, City c1, Boolean b) {
			location = c;
			destination = c1;
			task = b;
			actions = new ArrayList<>();
		}

	}
	@Override
	public void setup(Topology topology, TaskDistribution td, Agent agent) {

		// Reads the discount factor from the agents.xml file.
		// If the property is not present it defaults to 0.95
		Double discount = agent.readProperty("discount-factor", Double.class,
				0.95);

		this.random = new Random();
		this.pPickup = discount;
		this.numActions = 0;
		this.myAgent = agent;

		actions = new ArrayList<>();
		states = new ArrayList<>();
		for (City destination : topology) {
			actions.add(new Act(destination, true, 0));
			actions.add(new Act(destination, false, 0));
		}

		states = new ArrayList<>();
		for (City origin : topology) {
			for (City destination : topology) {
				if (origin != destination) {
					states.add(new State(origin, destination, true));
					states.add(new State(origin,null, false));
				}
			}
		}

		for (State state : states) {
			for (Act action: actions) {
				if (state.task && action.pickUp && action.destination == state.destination) {
					state.addAction(action);
				} else if (state.task && !action.pickUp && state.location.hasNeighbor(action.destination)) {
					state.addAction(action);
				} else if (!state.task && !action.pickUp && state.location.hasNeighbor(action.destination)) {
					state.addAction(action);

				}
			}
		}

		HashMap<State, Double> V = new HashMap<State, Double>();

		//Initialize V values
		for (State state : states) {
			V.put(state, 0.0);
		}
		int i = 0;
		HashMap<State, Double> temp = new HashMap<>();
		while (i < 20) {
			temp = V;
			++i;
			System.out.println(i);
			for (State state : states) {
				for (Act action : state.actions) {
						double qValue = 0.0;
						if (action.pickUp && state.task && action.destination == state.destination) {
							qValue += td.reward(state.location, state.destination) - state.location.distanceTo(state.destination);
						} else {
							qValue -= state.location.distanceTo(action.destination);
						}
						for (State nextState : V.keySet()) {
							if (nextState.location == action.destination && nextState.task) {
								qValue+= discount*td.probability(nextState.location,nextState.destination)*V.get(nextState);
							}
						}

						action.setReward(qValue);

				}

				V.put(state, state.getBestAction().reward);
			}

		}

		for (State state : states) {
			state.printState();
		}
	}

	@Override
	public Action act(Vehicle vehicle, Task availableTask) {
		Action action;
		City currentCity = vehicle.getCurrentCity();
		City nextCityNoTask = null;
		City nextCityTask = null;
		for (State state : states) {
			if (state.location == currentCity && !state.task) {
				state.printState();
				nextCityNoTask = state.getBestAction().getDestination();
			}
			if (state.location == currentCity && availableTask != null && state.destination == availableTask.deliveryCity) {
				state.printState();
				nextCityTask = state.getBestAction().getDestination();
			}
		}

		if (availableTask == null) {
			action = new Move(nextCityNoTask);
		} else {
			if (availableTask.deliveryCity.name == nextCityTask.name) {
				action = new Pickup(availableTask);
			} else {
				action = new Move(nextCityNoTask);
			}

		}
		
		if (numActions >= 1) {
			System.out.println("The total profit after "+numActions+" actions is "+myAgent.getTotalProfit()+" (average profit: "+(myAgent.getTotalProfit() / (double)numActions)+")");
		}
		numActions++;
		
		return action;
	}
}
