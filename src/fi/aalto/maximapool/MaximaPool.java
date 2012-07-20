package fi.aalto.maximapool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;

import utils.UpkeepThread;

/**
 * <p>
 * A simple servlet keeping maxima processes running and executing posted
 * commands in them.
 * </p>
 * 
 * @author Matti Harjula
 */
public class MaximaPool implements UpkeepThread.Maintainable {

	/** The configuration for the pool. */
	private MaximaProcessConfig processConfig;

	/** The configuration for the processes we create. */
	private MaximaPoolConfig poolConfig;

	/** The maintenance thread. */
	private UpkeepThread upKeep;

	/** Used to generate unique thread names. */
	static private long startCount = 0;

	/** Factory for Maxima processes. */
	private ProcessBuilder processBuilder;

	/** Estimated startup time (ms). */
	private long startupTimeEstimate = 2000;

	/** Estimated request frequency (Hz). */
	private double demandEstimate = 0.001;

	/** Used to restrict the number of processes starting up at any one time. */
	private volatile Semaphore startupThrotle;

	// These should probably be volatile, but then you would need to make sure
	// that the processes die though some other means.

	/** The pool for ready processes */
	private BlockingDeque<MaximaProcess> pool = new LinkedBlockingDeque<MaximaProcess>();

	/** The pool of processes in use */
	private List<MaximaProcess> usedPool = Collections
			.synchronizedList(new LinkedList<MaximaProcess>());

	/** The last few startup times, used to compute startupTimeEstimate. */
	private List<Long> startupTimeHistory = Collections
			.synchronizedList(new LinkedList<Long>());

	/** The last few request times, used to compute demandEstimate. */
	private List<Long> requestTimeHistory = Collections
			.synchronizedList(new LinkedList<Long>());

	/**
	 * @param poolConfig the configuration for the pool.
	 * @param processConfig the configuration for the processes we create.
	 */
	MaximaPool(MaximaPoolConfig poolConfig, MaximaProcessConfig processConfig) {

		this.poolConfig = poolConfig;
		this.processConfig = processConfig;

		// Initialise the datasets.
		startupTimeHistory.add(poolConfig.startupTimeInitialEstimate);
		requestTimeHistory.add(System.currentTimeMillis());

		// Set up the processBuilder
		processBuilder = new ProcessBuilder();
		processBuilder.command(processConfig.cmdLine.split(" "));
		processBuilder.directory(processConfig.cwd);
		processBuilder.redirectErrorStream(true);

		// Create the startup throttle.
		this.startupThrotle = new Semaphore(poolConfig.startupLimit);

		// Start the upkeep thread.
		upKeep = new UpkeepThread(this, poolConfig.updateCycle);
		upKeep.start();
	}

	void destroy() {
		// Kill the upkeep thread.
		try {
			upKeep.stopRunning();
		} catch (InterruptedException e) {
		}

		// Kill all running processes.
		for (MaximaProcess mp : pool) {
			mp.kill();
		}
		pool.clear();

		// Kill all used processes.
		for (MaximaProcess mp : usedPool) {
			mp.kill();
		}
		usedPool.clear();
	}

	/**
	 * Get a MaximaProcess from the pool.
	 */
	MaximaProcess getProcess() {
		requestTimeHistory.add(System.currentTimeMillis());

		// Start a new one as we are going to take one...
		if (startupThrotle.availablePermits() > 0) {
			startProcess();
		}

		MaximaProcess mp = null;
		while (mp == null) {
			try {
				mp = pool.take();
			} catch (InterruptedException e) {
				// If we failed to get one, wait a bit.
				e.printStackTrace();
				try {
					Thread.sleep(3);
				} catch (InterruptedException ee) {
					ee.printStackTrace();
				}
			}
		}
		usedPool.add(mp);
		mp.activate();

		return mp;
	}

	/**
	 * Low-level that creates a process in the current thread, and does not add
	 * it to the pool.
	 * @return the new process.
	 */
	MaximaProcess makeProcess() {
		return new MaximaProcess(processBuilder, processConfig);
	}

	/**
	 * Start a process asynchronously, and add it to the pool when done.
	 * @return the new process.
	 */
	void startProcess() {
		startCount++;
		String threadName = Thread.currentThread().getName() + "-starter-" + startCount;
		Thread starter = new Thread(threadName) {
			@Override
			public void run() {
				startupThrotle.acquireUninterruptibly();
				long startTime = System.currentTimeMillis();
				MaximaProcess mp = makeProcess();
				startupTimeHistory.add(System.currentTimeMillis() - startTime);
				pool.add(mp);
				startupThrotle.release();
			}
		};
		starter.start();
	}

	/**
	 * Start up as many processes as may be required to get the pool to the level
	 * it should be at.
	 * @param numProcessesRequired
	 */
	void startProcesses(double numProcessesRequired) {
		double numProcesses = pool.size() +
				poolConfig.startupLimit - startupThrotle.availablePermits();

		while (numProcesses < numProcessesRequired
				&& startupThrotle.availablePermits() > 0) {
			numProcesses += 1.0;
			startProcess();
		}
	}

	/**
	 * Use to tell us that a particular process has finished.
	 * @param mp the process that has finished.
	 */
	void notifyProcessFinishedWith(MaximaProcess mp) {
		usedPool.remove(mp);
	}

	@Override
	public void doMaintenance(long sleepTime) {
		killOverdueProcesses();
		double numProcessesRequired = updateEstimates(sleepTime);
		startProcesses(numProcessesRequired);
	}

	/**
	 * Maintenance task that detects stale processes that should be killed.
	 */
	void killOverdueProcesses() {
		long testTime = System.currentTimeMillis();

		// Kill off old ones
		MaximaProcess mp = null;
		try {
			mp = pool.take();
		} catch (InterruptedException e1) {
			mp = null;
		}
		while (mp != null && mp.isOverdue(testTime)) {
			mp.kill();
			try {
				mp = pool.take();
			} catch (InterruptedException e) {
				mp = null;
			}
		}
		if (mp != null) {
			pool.addFirst(mp);
		}

		while (usedPool.size() > 0 && usedPool.get(0).isOverdue(testTime)) {
			usedPool.remove(0).close();
		}
	}

	/**
	 * Maintenance task that updates the estimates that are used to manaage the pool.
	 */
	double updateEstimates(long sleep) {
		// Prune datasets
		while (startupTimeHistory.size() > poolConfig.averageCount) {
			startupTimeHistory.remove(0);
		}
		while (requestTimeHistory.size() > poolConfig.averageCount) {
			requestTimeHistory.remove(0);
		}

		// Do estimates
		startupTimeEstimate = 0;
		for (long t : startupTimeHistory) {
			startupTimeEstimate += t;
		}
		startupTimeEstimate /= startupTimeHistory.size();

		// +1 just to make sure that a startup moment exception can
		// be skipped
		demandEstimate = requestTimeHistory.size()
				/ ((System.currentTimeMillis() - requestTimeHistory.get(0)) + 1.0);

		// Guestimate demand for N
		double estimate = demandEstimate * poolConfig.safetyMultiplier * sleep;
		return Math.min(Math.max(estimate, poolConfig.poolMin), poolConfig.poolMax);
	}

	/**
	 * @return Map<String, String> a hash map containing data about
	 * the current state of the pool.
	 */
	Map<String, String> getStatus() {

		Map<String, String> status = new LinkedHashMap<String, String>();

		status.put("Processes starting up", "" +
				(poolConfig.startupLimit - startupThrotle.availablePermits()));
		status.put("Ready processes in the pool", "" + pool.size());
		status.put("Processes in use", "" + usedPool.size());
		status.put("Current demand estimate", demandEstimate * 1000.0 + " Hz");
		status.put("Current startuptime", startupTimeEstimate + " ms");

		return status;
	}
}
