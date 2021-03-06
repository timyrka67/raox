package ru.bmstu.rk9.rao.lib.dpt;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;

import ru.bmstu.rk9.rao.lib.database.Database;
import ru.bmstu.rk9.rao.lib.notification.Subscriber;
import ru.bmstu.rk9.rao.lib.notification.Subscription.SubscriptionType;
import ru.bmstu.rk9.rao.lib.pattern.Pattern;
import ru.bmstu.rk9.rao.lib.pattern.Rule;
import ru.bmstu.rk9.rao.lib.resource.ModelState;
import ru.bmstu.rk9.rao.lib.simulator.Simulator;
import ru.bmstu.rk9.rao.lib.simulator.Simulator.ExecutionState;
import ru.bmstu.rk9.rao.lib.simulator.Simulator.SimulatorState;

public class DecisionPointSearch<T extends ModelState<T>> extends DecisionPoint {
	private DecisionPoint.Condition terminate;

	private DatabaseRetriever<T> retriever;

	private boolean compareTops;

	private EvaluateBy evaluateBy;

	public DecisionPointSearch(String name, Condition condition, Condition terminate, EvaluateBy evaluateBy,
			boolean compareTops, DatabaseRetriever<T> retriever) {
		super(name, null, condition);
		this.terminate = terminate;
		this.evaluateBy = evaluateBy;
		this.retriever = retriever;
		this.compareTops = compareTops;

		Simulator.getSimulatorStateNotifier().addSubscriber(simulatorInitializedListener, SimulatorState.INITIALIZED,
				EnumSet.of(SubscriptionType.IGNORE_ACCUMULATED, SubscriptionType.ONE_SHOT));
	}

	private final Subscriber simulatorInitializedListener = new Subscriber() {
		@Override
		public void fireChange() {
			Simulator.getExecutionStateNotifier().addSubscriber(executionAbortedListener,
					ExecutionState.EXECUTION_ABORTED, EnumSet.of(SubscriptionType.ONE_SHOT));
			Simulator.getExecutionStateNotifier().addSubscriber(executionStartedListener,
					ExecutionState.EXECUTION_STARTED, EnumSet.of(SubscriptionType.ONE_SHOT));
		}
	};

	private final Subscriber executionStartedListener = new Subscriber() {
		@Override
		public void fireChange() {
			allowSearch = true;
		}
	};

	private final Subscriber executionAbortedListener = new Subscriber() {
		@Override
		public void fireChange() {
			allowSearch = false;
		}
	};

	private volatile boolean allowSearch = false;

	public static interface EvaluateBy {
		public double get();
	}

	public static abstract class Activity extends DecisionPoint.Activity {
		public enum ApplyMoment {
			before, after
		}

		public Activity(String name, ApplyMoment applyMoment) {
			super(name);
			this.applyMoment = applyMoment;
		}

		public abstract double calculateValue();

		public final ApplyMoment applyMoment;
	}

	private List<Activity> activities = new LinkedList<Activity>();

	public void addActivity(Activity a) {
		activities.add(a);
	}

	public static interface DatabaseRetriever<T extends ModelState<T>> {
		public T get();
	}

	public class ActivityInfo {
		private ActivityInfo(int number, Rule rule) {
			this.number = number;
			this.rule = rule;
		}

		public final int number;
		public final Rule rule;
	}

	private class GraphNode {
		private GraphNode(int number, GraphNode parent) {
			this.number = number;
			this.parent = parent;
		}

		final int number;
		final GraphNode parent;

		ActivityInfo activityInfo;

		double g;
		double h;

		T state;
	}

	private Comparator<GraphNode> nodeComparator = new Comparator<GraphNode>() {
		@Override
		public int compare(GraphNode x, GraphNode y) {
			if (x.g + x.h < y.g + y.h)
				return -1;
			if (x.g + x.h > y.g + y.h)
				return 1;

			return x.number - y.number;
		}
	};

	private PriorityQueue<GraphNode> nodesOpen = new PriorityQueue<GraphNode>(1, nodeComparator);
	private LinkedList<GraphNode> nodesClosed = new LinkedList<GraphNode>();

	private GraphNode head;
	private GraphNode current;

	private long memory;
	private long time;

	private int totalOpened;
	private int totalSpawned;
	private int totalAdded;

	@Override
	public boolean check() {
		time = System.currentTimeMillis();
		memory = Runtime.getRuntime().freeMemory();

		totalOpened = 0;
		totalSpawned = 0;
		totalAdded = 0;

		serializeStart();

		if (!allowSearch)
			return stop(StopCode.ABORTED);

		if (condition != null && !condition.check() || terminate.check())
			return stop(StopCode.CONDITION);

		nodesOpen.clear();
		nodesClosed.clear();

		head = new GraphNode(totalAdded++, null);
		head.state = retriever.get();
		nodesOpen.add(head);

		while (!nodesOpen.isEmpty()) {
			if (!allowSearch)
				return stop(StopCode.ABORTED);

			current = nodesOpen.poll();
			nodesClosed.add(current);
			current.state.deploy();

			totalOpened++;
			serializeOpen(current);

			if (terminate.check())
				return stop(StopCode.SUCCESS);

			nodesOpen.addAll(spawnChildren(current));
		}

		head.state.deploy();
		return stop(StopCode.FAIL);
	}

	public static enum SpawnStatus {
		NEW, WORSE, BETTER
	}

	private LinkedList<GraphNode> spawnChildren(GraphNode parent) {
		LinkedList<GraphNode> children = new LinkedList<GraphNode>();

		for (int activityNumber = 0; activityNumber < activities.size(); activityNumber++) {
			Activity a = activities.get(activityNumber);
			double value = 0;

			if (!a.checkActivity())
				continue;

			GraphNode newChild = new GraphNode(totalAdded, parent);

			totalSpawned++;

			SpawnStatus spawnStatus = SpawnStatus.NEW;

			if (a.applyMoment == Activity.ApplyMoment.before)
				value = a.calculateValue();

			newChild.state = parent.state.copy();
			newChild.state.deploy();

			Rule executed = a.executeActivity();
			newChild.activityInfo = new ActivityInfo(activityNumber, executed);

			if (a.applyMoment == Activity.ApplyMoment.after)
				value = a.calculateValue();

			newChild.g = parent.g + value;
			newChild.h = evaluateBy.get();

			add_child: {
				compare_tops: if (compareTops) {
					for (Collection<GraphNode> nodesList : Arrays.asList(nodesOpen, nodesClosed)) {
						for (GraphNode node : nodesList) {
							if (newChild.state.checkEqual(node.state)) {
								if (newChild.g < node.g) {
									nodesList.remove(node);
									spawnStatus = SpawnStatus.BETTER;
									break compare_tops;
								} else {
									spawnStatus = SpawnStatus.WORSE;
									break add_child;
								}
							}
						}
					}
				}

				children.add(newChild);
				totalAdded++;
			}

			serializeTops(newChild, spawnStatus, newChild.activityInfo, value);

			Simulator.getExecutionStateNotifier().notifySubscribers(ExecutionState.SEARCH_STEP);

			parent.state.deploy();
		}

		return children;
	}

	public static enum StopCode {
		ABORTED, CONDITION, SUCCESS, FAIL
	}

	private boolean stop(StopCode code) {
		double finalCost;

		switch (code) {
		case ABORTED:
			if (head != null)
				head.state.deploy();
			if (current != null)
				finalCost = current.g;
			else
				finalCost = 0;
			break;
		case CONDITION:
			finalCost = 0;
			break;
		case SUCCESS:
			databaseAddDecision();
		default:
			finalCost = current.g;
			break;
		}

		serializeStop(code, finalCost);

		return false;
	}

	private void databaseAddDecision() {
		LinkedList<GraphNode> decision = new LinkedList<GraphNode>();
		GraphNode node = current;

		while (node != head) {
			decision.add(node);
			node = node.parent;
		}

		serializeDecision(decision);
	}

	public enum SerializationLevel implements Comparable<SerializationLevel> {
		ALL("all", 4), TOPS("tops", 2), DECISION("decision", 1), START_STOP("start/stop", 0);

		private SerializationLevel(String name, int comparisonValue) {
			this.name = name;
			this.comparisonValue = comparisonValue;
		}

		private final String name;
		private final int comparisonValue;

		@Override
		public String toString() {
			return name;
		}
	}

	private final Comparator<SerializationLevel> serializationLevelComparator = new Comparator<SerializationLevel>() {
		@Override
		public int compare(SerializationLevel o1, SerializationLevel o2) {
			return o1.comparisonValue - o2.comparisonValue;
		}
	};

	private final boolean enoughSensitivity(SerializationLevel checkedType) {
		for (SerializationLevel type : SerializationLevel.values()) {
			if (Simulator.getDatabase().sensitiveTo(getName() + "." + type.toString()))
				if (serializationLevelComparator.compare(type, checkedType) >= 0)
					return true;
		}

		return false;
	}

	private final void serializeStart() {
		if (!enoughSensitivity(SerializationLevel.START_STOP))
			return;

		Simulator.getDatabase().addSearchEntry(this, Database.SearchEntryType.BEGIN, null);
	}

	private final void serializeStop(StopCode code, double finalCost) {
		if (!enoughSensitivity(SerializationLevel.START_STOP))
			return;

		ByteBuffer data = ByteBuffer.allocate(Database.TypeSize.BYTE + Database.TypeSize.DOUBLE
				+ Database.TypeSize.INTEGER * 4 + Database.TypeSize.LONG * 2);

		data.put((byte) code.ordinal()).putLong(System.currentTimeMillis() - time)
				.putLong(memory - Runtime.getRuntime().freeMemory()).putDouble(finalCost).putInt(totalOpened)
				.putInt(nodesOpen.size() + nodesClosed.size()).putInt(totalAdded).putInt(totalSpawned);

		Simulator.getDatabase().addSearchEntry(this, Database.SearchEntryType.END, data);
	}

	private final void serializeOpen(GraphNode node) {
		if (node != head && enoughSensitivity(SerializationLevel.TOPS)) {
			ByteBuffer data = ByteBuffer.allocate(Database.TypeSize.INTEGER * 2 + Database.TypeSize.DOUBLE * 2);

			data.putInt(node.number).putInt(node.parent.number).putDouble(node.g).putDouble(node.h);

			Simulator.getDatabase().addSearchEntry(this, Database.SearchEntryType.OPEN, data);
		}
	}

	private final void serializeTops(GraphNode node, SpawnStatus spawnStatus, ActivityInfo activityInfo, double value) {
		if (enoughSensitivity(SerializationLevel.TOPS)) {
			int[] relevantResources = node.activityInfo.rule.getRelevantInfo();

			ByteBuffer data = ByteBuffer.allocate(Database.TypeSize.BYTE + Database.TypeSize.DOUBLE * 3
					+ Database.TypeSize.INTEGER * (3 + relevantResources.length));

			data.put((byte) spawnStatus.ordinal()).putInt(node.number).putInt(node.parent.number).putDouble(node.g)
					.putDouble(node.h).putInt(activityInfo.number).putDouble(value);

			for (int relres : relevantResources)
				data.putInt(relres);

			Simulator.getDatabase().addSearchEntry(this, Database.SearchEntryType.SPAWN, data);
		}

		if (enoughSensitivity(SerializationLevel.ALL)) {
			activityInfo.rule.addResourceEntriesToDatabase(Pattern.ExecutedFrom.SEARCH, this.getName());
		}
	}

	private final void serializeDecision(LinkedList<GraphNode> decision) {
		if (!enoughSensitivity(SerializationLevel.DECISION))
			return;

		GraphNode node;
		for (Iterator<GraphNode> it = decision.descendingIterator(); it.hasNext();) {
			node = it.next();

			Rule rule = node.activityInfo.rule;
			int[] relevantResources = rule.getRelevantInfo();

			ByteBuffer data = ByteBuffer.allocate(Database.TypeSize.INTEGER * (2 + relevantResources.length));

			data.putInt(node.number).putInt(node.activityInfo.number);

			for (int relres : relevantResources)
				data.putInt(relres);

			Simulator.getDatabase().addSearchEntry(this, Database.SearchEntryType.DECISION, data);

			if (enoughSensitivity(SerializationLevel.ALL)) {
				rule.addResourceEntriesToDatabase(Pattern.ExecutedFrom.SOLUTION, this.getName());
			}
		}
	}
}
