package org.opendedup.sdfs.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.servers.HCServer;

public class HashClientPool {

	private HCServer server;
	private int poolSize;
	private LinkedBlockingQueue<HashClient> passiveObjects = null;
	private ArrayList<HashClient> activeObjects = new ArrayList<HashClient>();
	private ReentrantLock alock = new ReentrantLock();
	private byte id;
	private boolean suspect = false;

	public HashClientPool(HCServer server, String name, int size, byte id)
			throws IOException {
		this.server = server;
		this.id = id;
		this.poolSize = size;
		passiveObjects = new LinkedBlockingQueue<HashClient>(this.poolSize);
		this.populatePool();

	}

	public boolean isSuspect() {
		return this.suspect;
	}

	public void populatePool() throws IOException {
		for (int i = 0; i < poolSize; i++) {
			try {

				this.passiveObjects.add(this.makeObject());
			} catch (Exception e) {
				SDFSLogger.getLog().error("Unable to get object out of pool ",
						e);
				throw new IOException(e.toString());

			} finally {
			}
		}
	}

	public void activateObject(HashClient hc) throws IOException {
		if (hc.isClosed()) {
			hc.openConnection();
		}
	}

	public boolean validateObject(HashClient hc) {
		return false;
	}

	public HashClient borrowObject() throws IOException,
			HashClientSuspectException {
		if (suspect)
			throw new HashClientSuspectException(server);
		HashClient hc = null;

		try {
			hc = this.passiveObjects.take();
		} catch (InterruptedException e1) {

		}
		if (hc == null) {
			hc = this.makeObject();
		}
		if (hc.isClosed())
			hc.openConnection();
		this.alock.lock();
		try {
			this.activeObjects.add(hc);
		} catch (Exception e) {
			SDFSLogger.getLog().error("Unable to get object out of pool ", e);
			throw new IOException(e.toString());

		} finally {
			alock.unlock();
		}
		return hc;
	}

	public void returnObject(HashClient hc) {
		alock.lock();
		try {

			this.activeObjects.remove(hc);
		} catch (Exception e) {
			SDFSLogger.getLog().error(
					"Unable to get object out of active pool ", e);

		} finally {
			alock.unlock();
		}
		try {
			if (hc.isSuspect()) {
				hc.close();
				if (this.passiveObjects.size() == 0
						&& this.activeObjects.size() == 0) {
					this.suspect = true;
					SDFSLogger.getLog().warn(
							"DSEServer " + server.getHostName() + ":"
									+ server.getPort() + " is suspect");
				}

			} else if (this.passiveObjects.size() < this.poolSize) {

				if (!hc.isClosed())
					this.passiveObjects.put(hc);
				else {
					try {
						if (!this.suspect)

							this.passiveObjects.put(this.makeObject());
					} catch (Exception e) {
						this.suspect = true;
						SDFSLogger
								.getLog()
								.warn("unable to create connection to put into pool. Server is probably down.");
						try {
							hc.close();
						} catch (Exception e1) {
						}
					}
				}
			} else
				try {
					hc.close();
				} catch (Exception e1) {
				}
		} catch (Exception e) {
			SDFSLogger.getLog().warn("Unable to return object out of pool ", e);

		} finally {
		}
	}

	public HashClient makeObject() throws IOException {
		HashClient hc = new HashClient(this.server, "server", Main.DSEPassword,
				this.id, this);
		hc.openConnection();
		return hc;
	}

	public void destroyObject(HashClient hc) {
		hc.close();
	}

	public void close() throws IOException, InterruptedException {
		if (this.activeObjects.size() > 0)
			throw new IOException("Cannot close because writes still occuring");
		HashClient hc = passiveObjects.poll();
		while (hc != null) {
			hc.close();
			hc = passiveObjects.poll();
		}
	}

}
