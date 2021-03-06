package org.opendedup.collections;

import java.io.File;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.io.SyncFailedException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.opendedup.collections.AbstractShard;
import org.opendedup.collections.HashtableFullException;
import org.opendedup.collections.KeyNotFoundException;
import org.opendedup.hashing.HashFunctionPool;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.filestore.ChunkData;
import org.opendedup.util.LargeBloomFilter;
import org.opendedup.util.StorageUnit;

import objectexplorer.MemoryMeasurer;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.google.common.hash.PrimitiveSink;

public class ProgressiveFileByteArrayLongMap implements AbstractShard, Serializable,
		Runnable {
	private static final long serialVersionUID = 1L;
	transient MappedByteBuffer keys = null;
	transient private int size = 0;
	transient private String path = null;
	transient private FileChannel kFC = null;
	transient protected static final int EL = HashFunctionPool.hashLength + 8;
	transient private static final int VP = HashFunctionPool.hashLength;
	transient private ReentrantLock hashlock = new ReentrantLock();
	transient public static byte[] FREE = new byte[HashFunctionPool.hashLength];
	transient public static byte[] REMOVED = new byte[HashFunctionPool.hashLength];
	transient private int iterPos = 0;
	transient private boolean closed = false;
	transient private BitSet claims = null;
	transient private BitSet removed = null;
	transient private BitSet mapped = null;
	transient private AtomicInteger sz = new AtomicInteger(0);
	transient BloomFilter<KeyBlob> bf = null;
	transient boolean runningGC;
	transient long bgst = 0;
	transient protected boolean full = false;
	transient private long lastloaded = 0;
	transient private static long minTmBetweenLoads = 5 * 60* 1000;
	transient boolean active = false;

	static {
		FREE = new byte[HashFunctionPool.hashLength];
		REMOVED = new byte[HashFunctionPool.hashLength];
		Arrays.fill(FREE, (byte) 0);
		Arrays.fill(REMOVED, (byte) 1);
	}

	public ProgressiveFileByteArrayLongMap(String path, int size) throws IOException {
		this.size = size;
		this.path = path;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#iterInit()
	 */
	@Override
	public synchronized void iterInit() {
		this.iterPos = 0;
	}

	public boolean mightContain(byte[] key) {
		this.hashlock.lock();
		KeyBlob kb = new KeyBlob(key);

		if (!runningGC && !bf.mightContain(kb))
			return false;
		else
			return true;
	}

	public boolean isFull() {
		if (full)
			return true;
		return this.sz.get() >= (size * .75);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#nextKey()
	 */
	@Override
	public byte[] nextKey() {
		while (iterPos < size) {
			this.hashlock.lock();
			try {
				if (this.mapped.get(iterPos)) {
					byte[] key = new byte[FREE.length];
					keys.position(iterPos * EL);
					keys.get(key);
					iterPos++;
					if (Arrays.equals(key, REMOVED)) {
						this.removed.set(iterPos - 1);
						this.mapped.clear(iterPos - 1);
					} else if (!Arrays.equals(key, FREE)) {
						this.mapped.set(iterPos - 1);
						this.removed.clear(iterPos - 1);
						this.bf.put(new KeyBlob(key));
						return key;
					} else {
						this.mapped.clear(iterPos - 1);
					}
				} else {
					iterPos++;
				}
			} finally {
				this.hashlock.unlock();
			}

		}
		return null;
	}

	public KVPair nextKeyValue() {
		while (iterPos < size) {
			this.hashlock.lock();
			try {
				if (this.mapped.get(iterPos)) {
					byte[] key = new byte[FREE.length];
					keys.position(iterPos * EL);
					keys.get(key);
					iterPos++;
					if (Arrays.equals(key, REMOVED)) {
						this.removed.set(iterPos - 1);
						this.mapped.clear(iterPos - 1);
					} else if (!Arrays.equals(key, FREE)) {
						this.mapped.set(iterPos - 1);
						this.removed.clear(iterPos - 1);
						this.bf.put(new KeyBlob(key));
						KVPair p = new KVPair();
						p.key = key;
						p.value = keys.getLong();
						return p;
					} else {
						this.mapped.clear(iterPos - 1);
					}
				} else {
					iterPos++;
				}
			} finally {
				this.hashlock.unlock();
			}

		}
		return null;
	}

	public byte[] _nextKey() {
		while (iterPos < size) {
			this.hashlock.lock();
			try {
				byte[] key = new byte[FREE.length];
				keys.position(iterPos * EL);
				keys.get(key);
				iterPos++;
				if (Arrays.equals(key, REMOVED)) {
					this.removed.set(iterPos - 1);
					this.mapped.clear(iterPos - 1);
				} else if (!Arrays.equals(key, FREE)) {
					this.mapped.set(iterPos - 1);
					this.removed.clear(iterPos - 1);
					this.bf.put(new KeyBlob(key));
					return key;
				} else {
					this.mapped.clear(iterPos - 1);
				}
			} finally {
				this.hashlock.unlock();
			}

		}
		return null;
	}

	private void recreateMap() {
		this.full = true;
		mapped = new BitSet(size);
		mapped.clear();
		removed = new BitSet(size);
		removed.clear();
		int bsz = (int)(size * .75);
		bf = BloomFilter.create(kbFunnel, bsz, .01);
		this.iterInit();
		byte[] key = this._nextKey();
		while (key != null)
			key = this._nextKey();
		SDFSLogger.getLog().warn(
				"Recovered Hashmap " + this.path + " entries = "
						+ mapped.cardinality());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#getBigestKey()
	 */
	@Override
	public long getBigestKey() throws IOException {
		this.iterInit();
		long _bgst = 0;
		try {
			this.hashlock.lock();
			while (iterPos < size) {
				long val = -1;
				keys.position((iterPos * EL) + VP);
				val = keys.getLong();
				iterPos++;
				if (val > _bgst)
					_bgst = val;
			}
		} finally {
			this.hashlock.unlock();
		}
		return _bgst;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#setUp()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public long setUp() throws IOException {
		File posFile = new File(path + ".keys");
		boolean newInstance = !posFile.exists();
		if (posFile.exists()) {
			int sz = (int) (posFile.length()) / (EL);
			if(sz != size) {
				SDFSLogger.getLog().warn("Resetting size of hashtable to [" + sz+ "] instead of [" + size + "]");
				this.size = sz;
			}
		}
		@SuppressWarnings("resource")
		RandomAccessFile _kRaf = new RandomAccessFile(path + ".keys", "rw");
		this.kFC = _kRaf.getChannel();
		try {
			/*
			 * Field fd = tRaf.getClass().getDeclaredField("fd");
			 * fd.setAccessible(true); NativePosixUtil.advise((FileDescriptor)
			 * fd.get(tRaf), 0, 0, NativePosixUtil.DONTNEED);
			 * NativePosixUtil.advise((FileDescriptor) fd.get(tRaf), 0, 0,
			 * NativePosixUtil.RANDOM); fd =
			 * kFC.getClass().getDeclaredField("fd"); fd.setAccessible(true);
			 * NativePosixUtil.advise((FileDescriptor) fd.get(kFC), 0, 0,
			 * NativePosixUtil.DONTNEED);
			 * NativePosixUtil.advise((FileDescriptor) fd.get(kFC), 0, 0,
			 * NativePosixUtil.RANDOM);
			 */
		} catch (Exception e) {
			SDFSLogger.getLog().fatal("unable to set advisory", e);
			throw new IOException(e);
		}

		boolean closedCorrectly = true;
		if (newInstance) {
			mapped = new BitSet(size);
			removed = new BitSet(size);
			int bsz = (int)(size * .75);
			bf = BloomFilter.create(kbFunnel, bsz, .01);
			this.full = false;
		} else {
			File f = new File(path + ".bpos");
			if (!f.exists()) {
				closedCorrectly = false;
				SDFSLogger.getLog().warn("bpos does not exist");
			} else {
				try {
					RandomAccessFile _bpos = new RandomAccessFile(path
							+ ".bpos", "rw");
					_bpos.seek(0);
					bgst = _bpos.readLong();
					this.full = _bpos.readBoolean();
					_bpos.close();
					f.delete();
				} catch (Exception e) {
					SDFSLogger.getLog().warn("bpos load error", e);
					closedCorrectly = false;
				}
			}
			f = new File(path + ".vmp");
			if (!f.exists()) {
				closedCorrectly = false;
				SDFSLogger.getLog().warn("vmp does not exist");
			} else {
				try {
					FileInputStream fin = new FileInputStream(f);
					ObjectInputStream oon = new ObjectInputStream(fin);

					mapped = (BitSet) oon.readObject();
					oon.close();
				} catch (Exception e) {
					SDFSLogger.getLog().warn("vmp load error", e);
					closedCorrectly = false;
				}
				f.delete();
			}
			f = new File(path + ".vrp");
			if (!f.exists()) {
				closedCorrectly = false;
				SDFSLogger.getLog().warn("vrp does not exist");
			} else {
				try {
					FileInputStream fin = new FileInputStream(f);
					ObjectInputStream oon = new ObjectInputStream(fin);

					removed = (BitSet) oon.readObject();
					oon.close();
				} catch (Exception e) {
					SDFSLogger.getLog().warn("vrp load error", e);
					closedCorrectly = false;
				}
				f.delete();
			}
			f = new File(path + ".bf");
			if (!f.exists()) {
				closedCorrectly = false;
				SDFSLogger.getLog().warn("bf does not exist");
			} else {
				try {
					FileInputStream fin = new FileInputStream(f);
					ObjectInputStream oon = new ObjectInputStream(fin);
					bf = (BloomFilter<KeyBlob>) oon.readObject();
					oon.close();
				} catch (Exception e) {
					SDFSLogger.getLog().warn("bf load error", e);
					closedCorrectly = false;
				}
				f.delete();
			}
			if(SDFSLogger.isDebug()) {
			long mem = MemoryMeasurer
					.measureBytes(bf);
			SDFSLogger.getLog().info(
					"BF Archive Memory Size="
							+ StorageUnit.of(mem).format(mem));
			}
		}
		keys = this.kFC.map(MapMode.READ_WRITE, 0, size * EL);
		
		if (!closedCorrectly) {
			this.recreateMap();
		}
		if (bgst < 0) {
			SDFSLogger.getLog()
					.info("Hashtable " + path
							+ " did not close correctly. scanning ");
			bgst = this.getBigestKey();

		}
		sz.set(mapped.cardinality());

		claims = new BitSet(size);
		claims.clear();
		double pfull = (double)this.sz.get() /(double)size;
		SDFSLogger.getLog().debug("Percentage full="+pfull + " full=" + this.full);
		return bgst;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#containsKey(byte[])
	 */
	@Override
	public boolean containsKey(byte[] key) {
		try {
			this.hashlock.lock();
			KeyBlob kb = new KeyBlob(key);
			if (!runningGC && !bf.mightContain(kb))
				return false;
			int index = index(key);
			if (index >= 0) {
				int pos = (index / EL);
				this.claims.set(pos);
				if (this.runningGC)
					this.bf.put(kb);
				return true;
			}
			return false;
		} catch (Exception e) {
			SDFSLogger.getLog().fatal("error getting record", e);
			return false;
		} finally {
			this.hashlock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#isClaimed(byte[])
	 */
	@Override
	public boolean isClaimed(byte[] key) throws KeyNotFoundException,
			IOException {
		try {
			this.hashlock.lock();
			int index = index(key);
			if (index >= 0) {
				int pos = (index / EL);
				boolean cl = this.claims.get(pos);
				if (cl)
					return true;
			} else {
				throw new KeyNotFoundException(key);
			}
			return false;
		} finally {
			this.hashlock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#update(byte[], long)
	 */
	@Override
	public boolean update(byte[] key, long value) throws IOException {
		try {
			this.hashlock.lock();
			int pos = this.index(key);
			if (pos == -1) {
				return false;
			} else {
				// keys.position(pos);
				if (value > bgst)
					bgst = value;
				ByteBuffer lb = ByteBuffer.wrap(new byte[EL]);
				lb.put(key);
				lb.putLong(value);
				lb.position(0);
				// this.kFC.write(lb, pos);
				pos = (pos / EL);
				this.claims.set(pos);
				if (this.runningGC) {
					this.bf.put(new KeyBlob(key));
				}
				this.mapped.set(pos);
				this.removed.clear(pos);
				// this.store.position(pos);
				// this.store.put(storeID);
				return true;
			}
		} catch (Exception e) {
			SDFSLogger.getLog().fatal("error getting record", e);
			return false;
		} finally {
			this.hashlock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#remove(byte[])
	 */
	@Override
	public boolean remove(byte[] key) throws IOException {
		try {
			this.hashlock.lock();
			if (!this.runningGC && !bf.mightContain(new KeyBlob(key)))
				return false;
			int pos = this.index(key);

			if (pos == -1) {
				return false;
			}
			boolean claimed = this.claims.get(pos);
			if (claimed) {
				if (this.runningGC)
					this.bf.put(new KeyBlob(key));
				return false;
			} else {
				keys.position(pos);
				keys.put(REMOVED);
				keys.putLong(0);
				long fp = keys.getLong();

				ChunkData ck = new ChunkData(fp, key);
				if (ck.setmDelete(true)) {

					// this.kFC.write(rbuf, pos);
					pos = (pos / EL);
					this.claims.clear(pos);
					this.mapped.clear(pos);
					this.sz.decrementAndGet();
					this.removed.set(pos);
					// this.store.position(pos);
					// this.store.put((byte)0);
					return true;
				} else
					return false;
			}
		} catch (Exception e) {
			SDFSLogger.getLog().fatal("error getting record", e);
			return false;
		} finally {
			this.hashlock.unlock();
		}
	}

	private int hashFunc1(int hash) {
		return hash % size;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#hashFunc3(int)
	 */
	public int hashFunc3(int hash) {
		int result = hash + 1;
		return result;
	}

	private boolean isFree(int pos) {
		if (this.mapped.get(pos) || this.removed.get(pos))
			return false;
		else
			return true;
	}

	/**
	 * Locates the index of <tt>obj</tt>.
	 * 
	 * @param obj
	 *            an <code>Object</code> value
	 * @return the index of <tt>obj</tt> or -1 if it isn't in the set.
	 * @throws IOException
	 */
	byte[] current = new byte[FREE.length];

	private int index(byte[] key) throws IOException {

		// From here on we know obj to be non-null
		ByteBuffer buf = ByteBuffer.wrap(key);
		buf.position(8);
		int hash = buf.getInt() & 0x7fffffff;
		int index = this.hashFunc1(hash);
		if (this.isFree(index))
			return -1;
		else
			index = index * EL;

		keys.position(index);
		keys.get(current);

		if (Arrays.equals(current, key)) {
			return index;
		}

		if (Arrays.equals(current, FREE)) {

			SDFSLogger.getLog().warn("Should not be here in hashmap in index");
			return -1;
		}
		return indexRehashed(key, index, hash, current);
	}

	/**
	 * Locates the index of non-null <tt>obj</tt>.
	 * 
	 * @param obj
	 *            target key, know to be non-null
	 * @param index
	 *            we start from
	 * @param hash
	 * @param cur
	 * @return
	 * @throws IOException
	 */
	private int indexRehashed(byte[] key, int index, int hash, byte[] cur)
			throws IOException {

		// NOTE: here it has to be REMOVED or FULL (some user-given value)
		// see Knuth, p. 529
		int length = size * EL;
		int probe = (1 + (hash % (size - 2))) * EL;

		final int loopIndex = index;

		do {
			index -= probe;
			if (index < 0) {
				index += length;
			}
			if (!this.isFree(index / EL)) {

				keys.position(index);
				keys.get(cur);
				//
				if (Arrays.equals(cur, FREE)) {

					SDFSLogger.getLog().warn(
							"Should not be here in hashmap in indexRehashed");
					return -1;
				}
				//
				if (Arrays.equals(cur, key))
					return index;
			} else {
				return -1;
			}
		} while (index != loopIndex);

		return -1;
	}

	protected int insertionIndex(byte[] key, boolean migthexist)
			throws IOException, HashtableFullException {
		ByteBuffer buf = ByteBuffer.wrap(key);
		buf.position(8);
		int hash = buf.getInt() & 0x7fffffff;
		int index = this.hashFunc1(hash);
		if (this.isFree(index))
			return index * EL;
		else
			index = index * EL;
		if (migthexist) {
			keys.position(index);
			keys.get(current);

			if (Arrays.equals(current, FREE)) {
				SDFSLogger.getLog().warn(
						"Should not be here in hashmap in insertionIndex");
				return index;
			} else if (Arrays.equals(current, key)) {
				return -index - 1; // already stored
			}
		}
		return insertKeyRehash(key, index, hash, current, migthexist);
	}

	/**
	 * Looks for a slot using double hashing for a non-null key values and
	 * inserts the value in the slot
	 * 
	 * @param key
	 *            non-null key value
	 * @param index
	 *            natural index
	 * @param hash
	 * @param cur
	 *            value of first matched slot
	 * @return
	 * @throws IOException
	 * @throws HashtableFullException
	 */
	private int insertKeyRehash(byte[] key, int index, int hash, byte[] cur,
			boolean mightexist) throws IOException, HashtableFullException {
		final int length = size * EL;
		final int probe = (1 + (hash % (size - 2))) * EL;
		final int loopIndex = index;
		int firstRemoved = -1;

		/**
		 * Look until FREE slot or we start to loop
		 */
		do {
			// Identify first removed slot

			if (this.removed.get(index / EL) && firstRemoved == -1) {
				firstRemoved = index;
				if (!mightexist)
					return index;
			}
			index -= probe;
			if (index < 0) {
				index += length;
			}

			// A FREE slot stops the search
			if (this.isFree(index / EL)) {
				if (firstRemoved != -1) {
					return firstRemoved;
				} else {
					return index;
				}
			}
			if (mightexist) {
				keys.position(index);
				keys.get(cur);
				if (Arrays.equals(cur, key)) {
					return -index - 1;
				}
			}
			// Detect loop
		} while (index != loopIndex);

		// We inspected all reachable slots and did not find a FREE one
		// If we found a REMOVED slot we return the first one found
		if (firstRemoved != -1) {
			return firstRemoved;
		}

		// Can a resizing strategy be found that resizes the set?
		throw new HashtableFullException(
				"No free or removed slots available. Key set full?!!");
	}

	// transient ByteBuffer zlb = ByteBuffer.wrap(new byte[EL]);

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#put(byte[], long)
	 */
	@Override
	public boolean put(ChunkData cm) throws HashtableFullException, IOException {
		try {
			byte[] key = cm.getHash();
			this.hashlock.lock();
			if (this.full || this.sz.get() >= size) {
				this.full = true;
				throw new HashtableFullException(
						"entries is greater than or equal to the maximum number of entries. You need to expand"
								+ "the volume or DSE allocation size");
			}
			KeyBlob kb = new KeyBlob(key);
			int pos = -1;
			try {
				if (this.runningGC)
					pos = this.insertionIndex(key, true);
				else {
					pos = this.insertionIndex(key, bf.mightContain(kb));
				}
			} catch (HashtableFullException e) {
				this.full = true;
				throw e;
			}
			if (pos < 0) {

				int npos = -pos - 1;
				npos = (npos / EL);
				this.claims.set(npos);
				this.bf.put(kb);
				return false;
			} else {
				if (!cm.recoverd) {
					try {
					cm.persistData(true);
					}catch(HashExistsException e) {
						return false;
					}
				}
				this.keys.position(pos);
				this.keys.put(key);
				this.keys.putLong(cm.getcPos());
				if (cm.getcPos() > bgst)
					bgst = cm.getcPos();
				pos = (pos / EL);
				this.claims.set(pos);
				this.mapped.set(pos);
				this.sz.incrementAndGet();
				this.removed.clear(pos);
				this.bf.put(kb);
			}
			// this.store.position(pos);
			// this.store.put(storeID);
			return pos > -1 ? true : false;
		} finally {
			this.hashlock.unlock();
		}
	}

	public boolean put(byte[] key, long value) throws HashtableFullException,
			IOException {
		try {
			this.hashlock.lock();
			if (this.full || this.sz.get() >= size) {
				this.full = true;
				throw new HashtableFullException(
						"entries is greater than or equal to the maximum number of entries. You need to expand"
								+ "the volume or DSE allocation size");

			}
			KeyBlob kb = new KeyBlob(key);
			int pos = -1;
			try {
				if (this.runningGC)
					pos = this.insertionIndex(key, true);
				else {
					pos = this.insertionIndex(key, bf.mightContain(kb));
				}
			} catch (HashtableFullException e) {
				this.full = true;
				throw e;
			}
			if (pos < 0) {
				int npos = -pos - 1;
				npos = (npos / EL);
				this.claims.set(npos);
				this.bf.put(kb);
				return false;
			} else {
				this.keys.position(pos);
				this.keys.put(key);
				this.keys.putLong(value);
				if (value > bgst)
					bgst = value;
				pos = (pos / EL);
				this.claims.set(pos);
				this.mapped.set(pos);
				this.sz.incrementAndGet();
				this.removed.clear(pos);
				this.bf.put(kb);
				// this.store.position(pos);
				// this.store.put(storeID);
				return pos > -1 ? true : false;
			}
		} finally {
			this.hashlock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#getEntries()
	 */
	@Override
	public int getEntries() {
		return this.sz.get();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#get(byte[])
	 */
	@Override
	public long get(byte[] key) {
		return this.get(key, true);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#get(byte[], boolean)
	 */
	@Override
	public long get(byte[] key, boolean claim) {
		try {
			this.hashlock.lock();
			if (key == null)
				return -1;
			if (!this.runningGC && !this.bf.mightContain(new KeyBlob(key)))
				return -1;
			int pos = this.index(key);
			if (pos == -1) {
				return -1;
			} else {
				this.keys.position(pos + VP);
				long val = this.keys.getLong();
				if (claim) {
					pos = (pos / EL);
					this.claims.set(pos);
				}
				if (this.runningGC)
					this.bf.put(new KeyBlob(key));
				return val;

			}
		} catch (Exception e) {
			SDFSLogger.getLog().fatal("error getting record", e);
			return -1;
		} finally {
			this.hashlock.unlock();
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#size()
	 */
	@Override
	public int size() {
		return this.sz.get();
	}

	public int avail() {
		return size - this.sz.get();
	}

	public int maxSize() {
		return this.size;
	}

	public String toString() {
		return this.path;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#close()
	 */
	@Override
	public void close() {
		this.hashlock.lock();
		try {
		this.closed = true;
		try {
			this.kFC.force(true);
			this.kFC.close();
		} catch (Exception e) {

		}
		try {
			File f = new File(path + ".vmp");
			FileOutputStream fout = new FileOutputStream(f);
			ObjectOutputStream oon = new ObjectOutputStream(fout);
			oon.writeObject(mapped);
			oon.flush();
			fout.getFD().sync();
			oon.close();
			fout.flush();

			fout.close();
		} catch (Exception e) {
			SDFSLogger.getLog().warn("error closing", e);
		}
		try {
			File f = new File(path + ".vrp");
			FileOutputStream fout = new FileOutputStream(f);
			ObjectOutputStream oon = new ObjectOutputStream(fout);
			oon.writeObject(this.removed);
			oon.flush();
			fout.getFD().sync();
			oon.close();
			fout.flush();

			fout.close();
		} catch (Exception e) {
			SDFSLogger.getLog().warn("error closing", e);
		}
		if (this.bf != null) {
			try {
				File f = new File(path + ".bf");
				FileOutputStream fout = new FileOutputStream(f);
				ObjectOutputStream oon = new ObjectOutputStream(fout);
				oon.writeObject(this.bf);
				oon.flush();
				fout.getFD().sync();
				oon.close();
				fout.flush();

				fout.close();
			} catch (Exception e) {
				SDFSLogger.getLog().warn("error closing", e);
			}
		}
		try {
			RandomAccessFile _bpos = new RandomAccessFile(path + ".bpos", "rw");
			_bpos.seek(0);
			_bpos.writeLong(bgst);
			_bpos.writeBoolean(full);
			_bpos.getFD().sync();
			_bpos.close();
		} catch (Exception e) {
			SDFSLogger.getLog().warn("error closing", e);
		}
		}finally {
		this.hashlock.unlock();
		}
		if (SDFSLogger.isDebug())
			SDFSLogger.getLog().debug("closed " + this.path);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#claimRecords()
	 */
	@Override
	public synchronized long claimRecords() throws IOException {
		if (this.closed)
			throw new IOException("Hashtable " + this.path + " is close");
		long k = 0;

		try {
			this.iterInit();
			while (iterPos < size) {
				this.hashlock.lock();
				try {
					boolean claimed = claims.get(iterPos);
					claims.clear(iterPos);
					if (claimed) {
						this.mapped.set(iterPos);
						this.removed.clear(iterPos);

						
						k++;
					}
				} finally {
					iterPos++;
					this.hashlock.unlock();
				}
			}
		} catch (NullPointerException e) {

		}

		return k;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.AbstractShard#sync()
	 */
	@Override
	public void sync() throws SyncFailedException, IOException {
		this.kFC.force(true);
	}

	

	public static class KeyBlob implements Serializable {
		/**
		 * 
		 */
		private static final long serialVersionUID = -2753966297671970793L;
		/**
		 * 
		 */
		public byte[] key;

		public KeyBlob(byte[] key) {
			this.key = key;
		}

		public byte[] getKey() {
			return this.key;
		}

		public void setKey(byte[] key) {
			this.key = key;
		}
	}

	Funnel<KeyBlob> kbFunnel = new Funnel<KeyBlob>() {
		/**
		 * 
		 */
		private static final long serialVersionUID = -1612304804452862219L;

		/**
		 * 
		 */

		@Override
		public void funnel(KeyBlob key, PrimitiveSink into) {
			into.putBytes(key.key);
		}
	};

	/*
	 * @Override public long claimRecords(BloomFilter<KeyBlob> bf) throws
	 * IOException { this.iterInit(); byte[] key = this.nextKey(); while (key !=
	 * null) { if (bf.mightContain(new KeyBlob(key))) { this.hashlock.lock();
	 * this.claims.set(this.iterPos - 1); this.hashlock.unlock(); } key =
	 * this.nextKey(); } this.iterInit(); return this.claimRecords(); }
	 */

	@Override
	public long claimRecords(LargeBloomFilter nbf) throws IOException {
		this.iterInit();
		long sz = 0;
		this.hashlock.lock();
		try {
			int bsz = (int)(size * .75);
			bf = BloomFilter.create(kbFunnel, bsz, .01);
			this.runningGC = true;
		} finally {
			this.hashlock.unlock();
		}
		try {
			while (iterPos < size) {
				this.hashlock.lock();
				try {
					byte[] key = new byte[FREE.length];
					keys.position(iterPos * EL);
					keys.get(key);
					long val = keys.getLong();
					if (!Arrays.equals(key, FREE)
							&& !Arrays.equals(key, REMOVED)) {
						if (!nbf.mightContain(key) && !this.claims.get(iterPos)) {
							keys.position(iterPos * EL);
							keys.put(REMOVED);
							keys.putLong(0);
							ChunkData ck = new ChunkData(val, key);
							ck.setmDelete(true);
							this.mapped.clear(iterPos);
							this.sz.decrementAndGet();
							this.removed.set(iterPos);
							sz++;
						} else {
							this.mapped.set(iterPos);
							bf.put(new KeyBlob(key));
						}
						this.claims.clear(iterPos);
					}

				} finally {
					iterPos++;
					this.hashlock.unlock();
				}
			}
			return sz;
		} finally {
			this.hashlock.lock();
			this.runningGC = false;
			this.hashlock.unlock();
		}
	}

	public long claimRecords(LargeBloomFilter nbf, LargeBloomFilter lbf)
			throws IOException {
		this.iterInit();
		long sz = 0;
		this.hashlock.lock();
		try {
			int bsz = (int)(size * .75);
			bf = BloomFilter.create(kbFunnel, bsz, .01);
			this.runningGC = true;
		} finally {
			this.hashlock.unlock();
		}
		try {
			while (iterPos < size) {
				this.hashlock.lock();
				try {
					byte[] key = new byte[FREE.length];
					keys.position(iterPos * EL);
					keys.get(key);
					long val = keys.getLong();
					if (!Arrays.equals(key, FREE)
							&& !Arrays.equals(key, REMOVED)) {
						if (!nbf.mightContain(key) && !this.claims.get(iterPos)) {
							keys.position(iterPos * EL);
							keys.put(REMOVED);
							keys.putLong(0);
							ChunkData ck = new ChunkData(val, key);
							ck.setmDelete(true);
							this.mapped.clear(iterPos);
							this.sz.decrementAndGet();
							this.removed.set(iterPos);
							sz++;
						} else {
							this.mapped.set(iterPos);
							bf.put(new KeyBlob(key));
							lbf.put(key);
						}
						this.claims.clear(iterPos);
					}

				} finally {
					iterPos++;
					this.hashlock.unlock();
				}
			}
			return sz;
		} finally {
			this.hashlock.lock();
			this.runningGC = false;
			this.hashlock.unlock();
		}
	}

	@Override
	public boolean equals(Object object) {
		boolean sameSame = false;

		if (object != null && object instanceof ProgressiveFileByteArrayLongMap) {
			ProgressiveFileByteArrayLongMap m = (ProgressiveFileByteArrayLongMap) object;
			sameSame = this.path.equalsIgnoreCase(m.path);
		} 
		return sameSame;
	}

	public void vanish() {
		this.close();
		File f = new File(path + ".keys");
		f.delete();
		f = new File(path + ".bpos");
		f.delete();
		f = new File(path + ".vmp");
		f.delete();
		f = new File(path + ".vrp");
		f.delete();
		f = new File(path + ".bf");
		f.delete();
	}

	private boolean stopRun = false;
	private boolean clRunning = false;

	public void stopRun() {
		this.stopRun = true;
	}

	
	@Override
	public void run() {
		long tm = System.currentTimeMillis();
		if((tm - this.lastloaded) > minTmBetweenLoads) {
		try {
			if (!clRunning) {
				if(SDFSLogger.isDebug())
				SDFSLogger.getLog().debug("caching " + this.path);
				clRunning = true;
				int _iterPos = 0;

				while (!stopRun && _iterPos < size) {
					this.hashlock.lock();
					try {
						if (this.mapped.get(iterPos)) {
							byte[] key = new byte[FREE.length];
							keys.position(iterPos * EL);
							keys.get(key);
							keys.getLong();
							_iterPos++;

						} else {
							_iterPos++;
						}
					} finally {
						this.hashlock.unlock();
					}

				}
				if(SDFSLogger.isDebug())
					SDFSLogger.getLog().debug("done caching " + this.path);
			}
		} finally {
			this.lastloaded = System.currentTimeMillis();
			clRunning = false;
			this.stopRun = false;
		}
		}

	}
}
