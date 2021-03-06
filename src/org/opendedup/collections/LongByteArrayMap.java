package org.opendedup.collections;

import java.io.File;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import org.opendedup.hashing.HashFunctionPool;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.io.HashLocPair;
import org.opendedup.util.OSValidator;

public class LongByteArrayMap implements DataMapInterface {
	private static final int _arrayLength = (1 + HashFunctionPool.hashLength + 1 + 8)
			* HashFunctionPool.max_hash_cluster;
	public static final int _v1arrayLength = 4 + ((HashFunctionPool.hashLength + 8) * HashFunctionPool.max_hash_cluster);
	public static final int MAX_ELEMENTS_PER_AR = HashFunctionPool.max_hash_cluster*2;
	private static final int _v2arrayLength = 1 + 4 + 4 + 4 +(HashLocPair.BAL * MAX_ELEMENTS_PER_AR);
	private static final int _v1offset = 64;
	private static final int _v2offset = 256;
	private static final short magicnumber = 6442;
	String filePath = null;
	private ReentrantLock hashlock = new ReentrantLock();
	private boolean closed = true;
	public static byte[] _FREE = new byte[_arrayLength];
	public static byte[] _V1FREE = new byte[_v1arrayLength];
	public static byte[] _V2FREE = new byte[_v2arrayLength];
	public long iterPos = 0;
	FileChannel bdbc = null;
	// private int maxReadBufferSize = Integer.MAX_VALUE;
	// private int eI = 1024 * 1024;
	// private long endPos = maxReadBufferSize;
	File dbFile = null;
	Path bdbf = null;
	// FileChannel iterbdb = null;
	FileChannel pbdb = null;
	RandomAccessFile rf = null;
	private int offset = 0;
	private int arrayLength = _v1arrayLength;
	private byte version = Main.MAPVERSION;
	private byte[] FREE;
	long flen = 0;

	static {
		SDFSLogger.getLog().info("File Map Version is = " +Main.MAPVERSION);
		_FREE = new byte[_arrayLength];
		_V1FREE = new byte[_v1arrayLength];
		_V2FREE = new byte[_v2arrayLength];
		Arrays.fill(_FREE, (byte) 0);
		Arrays.fill(_V1FREE, (byte) 0);
		Arrays.fill(_V2FREE, (byte) 0);
	}

	public LongByteArrayMap(String filePath) throws IOException {
		this.filePath = filePath;
		this.openFile();

	}

	public byte getVersion() {
		return this.version;
	}

	public byte[] getFree() {
		return this.FREE;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#iterInit()
	 */
	@Override
	public void iterInit() throws IOException {
		iterlock.lock();
		try {
			this.iterPos = 0;

		} finally {
			iterlock.unlock();
		}
	}

	private long getInternalIterFPos() {
		return (this.iterPos * arrayLength) + this.offset;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#getIterPos()
	 */
	@Override
	public long getIterPos() {
		return (this.iterPos * arrayLength);
	}

	private ReentrantLock iterlock = new ReentrantLock();

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#nextKey()
	 */
	@Override
	public long nextKey() throws IOException {
		iterlock.lock();
		try {
			long _cpos = getInternalIterFPos();
			while (_cpos < flen) {
				try {
					ByteBuffer buf = ByteBuffer.wrap(new byte[arrayLength]);
					long pos = iterPos * Main.CHUNK_LENGTH;
					pbdb.read(buf, _cpos);
					byte[] val = buf.array();
					iterPos++;
					if (!Arrays.equals(val, FREE)) {
						return pos;
					}
				} catch (Exception e1) {
					if (SDFSLogger.isDebug())
						SDFSLogger.getLog().debug(
								"unable to iterate through key at " + iterPos
										* arrayLength, e1);
				} finally {
					iterPos++;
					_cpos = getInternalIterFPos();
				}
			}
			if ((iterPos * arrayLength) + this.offset != flen)
				throw new IOException("did not reach end of file for ["
						+ this.filePath + "] len=" + iterPos * arrayLength
						+ " file len =" + flen);

			return -1;
		} finally {
			iterlock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#nextValue()
	 */
	@Override
	public byte[] nextValue() throws IOException {
		iterlock.lock();
		try {
			long _cpos = getInternalIterFPos();
			while (_cpos < flen) {
				try {
					ByteBuffer buf = ByteBuffer.wrap(new byte[arrayLength]);
					pbdb.read(buf, _cpos);
					byte[] val = buf.array();
					if (!Arrays.equals(val, FREE)) {
						return val;
					}
				} finally {
					iterPos++;
					_cpos = (iterPos * arrayLength) + this.offset;
				}
			}
			if (getInternalIterFPos() < pbdb.size()) {
				this.hashlock.lock();
				try {
					flen = this.pbdb.size();
				} finally {
					this.hashlock.unlock();
				}
				return this.nextValue();
			}
			return null;
		} finally {
			iterlock.unlock();
		}

	}

	public LongKeyValue nextKeyValue() throws IOException {
		iterlock.lock();
		try {
			long _cpos = getInternalIterFPos();
			while (_cpos < flen) {
				try {
					ByteBuffer buf = ByteBuffer.wrap(new byte[arrayLength]);
					pbdb.read(buf, _cpos);
					byte[] val = buf.array();
					if (!Arrays.equals(val, FREE)) {
						return new LongKeyValue(iterPos * Main.CHUNK_LENGTH,
								val);
					}
				} finally {
					iterPos++;
					_cpos = (iterPos * arrayLength) + this.offset;
				}
			}
			if (getInternalIterFPos() < pbdb.size()) {
				this.hashlock.lock();
				try {
					flen = this.pbdb.size();
				} finally {
					this.hashlock.unlock();
				}
				return this.nextKeyValue();
			}
			return null;
		} finally {
			iterlock.unlock();
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#isClosed()
	 */
	@Override
	public boolean isClosed() {
		return this.closed;
	}

	private void intVersion() {
		if (version == 0) {
			this.FREE = _FREE;
			this.offset = 0;
			this.arrayLength = _arrayLength;
		}
		if (version == 1) {
			this.FREE = _V1FREE;
			this.offset = _v1offset;
			this.arrayLength = _v1arrayLength;
		}
		if (version == 2) {
			this.FREE = _V2FREE;
			this.offset = _v2offset;
			this.arrayLength = _v2arrayLength;
		}
	}

	private void openFile() throws IOException {
		if (this.closed) {
			this.hashlock.lock();
			bdbf = Paths.get(filePath);
			try {
				dbFile = new File(filePath);
				boolean fileExists = dbFile.exists();
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog()
							.debug("opening [" + this.filePath + "]");
				if (!fileExists) {
					if (!dbFile.getParentFile().exists()) {
						dbFile.getParentFile().mkdirs();
					}
					FileChannel bdb = (FileChannel) Files.newByteChannel(bdbf,
							StandardOpenOption.CREATE,
							StandardOpenOption.WRITE, StandardOpenOption.READ,
							StandardOpenOption.SPARSE);
					if (version > 0) {
						// SDFSLogger.getLog().info("Writing version " +
						// this.version);
						ByteBuffer buf = ByteBuffer.allocate(3);
						buf.putShort(magicnumber);
						buf.put(this.version);
						buf.position(0);
						bdb.position(0);
						bdb.write(buf);
					}
					bdb.position(1024);
					bdb.close();
					flen = 0;
				} else {

					flen = dbFile.length();
				}
				rf = new RandomAccessFile(filePath, "rw");

				pbdb = rf.getChannel();
				ByteBuffer buf = ByteBuffer.allocate(3);
				pbdb.position(0);
				pbdb.read(buf);
				buf.position(0);
				if (buf.getShort() == magicnumber) {
					this.version = buf.get();
				} else {
					this.version = 0;
				}
				this.intVersion();
				// initiall allocate 32k
				this.closed = false;
			} catch (Exception e) {
				SDFSLogger.getLog().error("unable to open file " + filePath, e);
				throw new IOException(e);
			} finally {
				this.hashlock.unlock();
			}
		}
	}

	private long getMapFilePosition(long pos) throws IOException {
		long propLen = ((pos / Main.CHUNK_LENGTH) * FREE.length) + this.offset;
		return propLen;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#put(long, byte[])
	 */
	@Override
	public void put(long pos, byte[] data) throws IOException {
		if (this.isClosed()) {
			throw new IOException("hashtable [" + this.filePath + "] is close");
		}
		/*
		if (data.length != arrayLength)
			throw new IOException("data length " + data.length
					+ " does not equal " + arrayLength);
		*/
		long fpos = 0;
		fpos = this.getMapFilePosition(pos);

		//
		this.hashlock.lock();
		if (fpos > flen)
			flen = fpos;
		this.hashlock.unlock();
		// rf.seek(fpos);
		// rf.write(data);
		pbdb.write(ByteBuffer.wrap(data), fpos);
	}

	@Override
	public void putIfNull(long pos, byte[] data) throws IOException {
		byte[] b = this.get(pos);
		if (b == null) {
			this.put(pos, data);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#trim(long, int)
	 */
	@Override
	public synchronized void trim(long pos, int len) {

		double spos = Math.ceil(((double) pos / (double) Main.CHUNK_LENGTH));
		long ep = pos + len;
		double epos = Math.floor(((double) ep / (double) Main.CHUNK_LENGTH));
		long ls = ((long) spos * (long) FREE.length) + (long) this.offset;
		long es = ((long) epos * (long) FREE.length) + (long) this.offset;
		if (es <= ls)
			return;
		else {
			if (SDFSLogger.isDebug())
				SDFSLogger.getLog().debug("will trim from " + ls + " to " + es);
			FileChannel _bdb = null;
			ByteBuffer buff = ByteBuffer.wrap(this.FREE);
			try {
				_bdb = (FileChannel) Files.newByteChannel(bdbf,
						StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.READ, StandardOpenOption.SPARSE);
				_bdb.position(ls);
				while (_bdb.position() < es) {
					buff.position(0);
					_bdb.write(buff);
				}
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug(
							"trimed from " + ls + " to " + _bdb.position());
			}

			catch (Exception e) {
				SDFSLogger.getLog().error(
						"error while trim from " + ls + " to " + es, e);
			} finally {
				try {
					_bdb.close();
				} catch (Exception e) {
				}
			}

		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#truncate(long)
	 */
	@Override
	public void truncate(long length) throws IOException {
		this.hashlock.lock();
		long fpos = 0;
		FileChannel _bdb = null;
		try {
			fpos = this.getMapFilePosition(length);
			_bdb = (FileChannel) Files.newByteChannel(bdbf,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.READ, StandardOpenOption.SPARSE);
			_bdb.truncate(fpos);
		} catch (Exception e) {
			// System.exit(-1);
			throw new IOException(e);
		} finally {
			try {
				_bdb.close();
			} catch (Exception e) {
			}
			this.flen = fpos;
			this.hashlock.unlock();
		}

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.collections.AbstractMap#remove(long)
	 */
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#remove(long)
	 */
	@Override
	public void remove(long pos) throws IOException {
		if (this.isClosed()) {
			throw new IOException("hashtable [" + this.filePath + "] is close");
		}
		this.hashlock.lock();
		long fpos = 0;
		FileChannel _bdb = null;
		try {
			fpos = this.getMapFilePosition(pos);
			_bdb = (FileChannel) Files.newByteChannel(bdbf,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.READ, StandardOpenOption.SPARSE);
			_bdb.write(ByteBuffer.wrap(FREE), fpos);
		} catch (Exception e) {
			throw new IOException(e);
		} finally {
			try {
				_bdb.close();
			} catch (Exception e) {
			}
			this.hashlock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.collections.AbstractMap#get(long)
	 */
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#get(long)
	 */
	@Override
	public byte[] get(long pos) throws IOException {
		if (this.isClosed()) {
			throw new IOException("hashtable [" + this.filePath + "] is close");
		}

		long fpos = 0;
		try {
			fpos = this.getMapFilePosition(pos);

			if (fpos > flen)
				return null;
			byte [] buf = null;
			if(version == 2) {
				ByteBuffer bf = ByteBuffer.allocate(5);
				pbdb.read(bf, fpos);
				bf.position(1);
				buf =new byte[bf.getInt()];
				if(buf.length == 0)
					return null;
			}
			else
				buf = new byte[arrayLength];
			pbdb.read(ByteBuffer.wrap(buf), fpos);
			if (Arrays.equals(buf, FREE))
				return null;
			return buf;
		} catch (BufferUnderflowException e) {
			return null;
		} catch (Exception e) {
			SDFSLogger.getLog().fatal(
					"error getting data at " + fpos + " buffer capacity="
							+ dbFile.length(), e);
			throw new IOException(e);
		} finally {

		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#sync()
	 */
	@Override
	public void sync() throws IOException {
		this.pbdb.force(false);

		/*
		 * FileChannel _bdb = null; try { _bdb = (FileChannel)
		 * bdbf.newByteChannel(StandardOpenOption.WRITE,
		 * StandardOpenOption.READ, StandardOpenOption.SPARSE);
		 * _bdb.force(true); } catch (IOException e) {
		 * 
		 * } finally { try { _bdb.close(); } catch (Exception e) { } }
		 */
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.annesam.collections.AbstractMap#vanish()
	 */

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#vanish()
	 */
	@Override
	public void vanish() throws IOException {
		this.hashlock.lock();
		try {
			if (!this.isClosed())
				this.close();
			File f = new File(this.filePath);
			f.delete();
		} catch (Exception e) {
			throw new IOException(e);
		} finally {
			this.hashlock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#copy(java.lang.String)
	 */
	@Override
	public void copy(String destFilePath) throws IOException {
		this.hashlock.lock();
		FileChannel srcC = null;
		FileChannel dstC = null;
		try {
			this.sync();
			if (SDFSLogger.isDebug())
				SDFSLogger.getLog().debug("copying to " + destFilePath);
			File dest = new File(destFilePath);
			File src = new File(this.filePath);
			if (dest.exists())
				dest.delete();
			else
				dest.getParentFile().mkdirs();
			if (OSValidator.isWindows()) {
				srcC = (FileChannel) Files.newByteChannel(
						Paths.get(src.getPath()), StandardOpenOption.READ,
						StandardOpenOption.SPARSE);
				dstC = (FileChannel) Files.newByteChannel(
						Paths.get(src.getPath()), StandardOpenOption.CREATE,
						StandardOpenOption.WRITE, StandardOpenOption.SPARSE);
				srcC.transferTo(0, src.length(), dstC);
			} else {
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug("snapping on unix/linux volume");
				String cpCmd = "cp --sparse=always --reflink=auto " + src.getPath() + " "
						+ dest.getPath();
				SDFSLogger.getLog().debug(cpCmd);
				Process p = Runtime.getRuntime().exec(cpCmd);
				int exitValue = p.waitFor();
				if (exitValue != 0) {
					throw new IOException("unable to copy " + src.getPath()
							+ " to  " + dest.getPath() + " exit value was "
							+ exitValue);
				}
				if (SDFSLogger.isDebug())
					SDFSLogger.getLog().debug(
							"copy exit value is " + p.waitFor());
			}
			if (SDFSLogger.isDebug())
				SDFSLogger.getLog().debug(
						"snapped map to [" + dest.getPath() + "]");
		} catch (Exception e) {
			throw new IOException(e);
		} finally {
			try {
				srcC.close();
			} catch (Exception e) {
			}
			try {
				dstC.close();
			} catch (Exception e) {
			}
			this.hashlock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#size()
	 */
	@Override
	public long size() {
		this.hashlock.lock();
		try {
			long sz = (this.dbFile.length() - this.offset) / this.arrayLength;
			return sz;
		} finally {
			this.hashlock.unlock();
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.opendedup.collections.DataMap#close()
	 */
	@Override
	public void close() {
		this.hashlock.lock();
		dbFile = null;
		if (!this.isClosed()) {
			this.closed = true;
		}
		try {
			pbdb.force(true);
			pbdb.close();
		} catch (Exception e) {
		} finally {
			this.hashlock.unlock();
		}
		try {
			this.rf.close();
		} catch (Exception e) {
		}
	}

	@Override
	public void put(long pos, byte[] data, int length) throws IOException {
		// TODO Auto-generated method stub
		
	}

	/*
	 * public static DataMapInterface convertToV1(LongByteArrayMap map,SDFSEvent
	 * evt) throws IOException { LongByteArrayMap m = new
	 * LongByteArrayMap(map.filePath +"-new",(byte)1); File of = map.dbFile;
	 * File nf = new File(map.filePath +"-new"); map.hashlock.lock(); try {
	 * map.iterInit(); evt.maxCt = map.size(); byte [] val = map.nextValue();
	 * while(val != null) { evt.curCt++; SparseDataChunk ck = new
	 * SparseDataChunk(val); SparseDataChunk _ck = new
	 * SparseDataChunk(ck.getDoop(), ck.getHash(), ck.isLocalData(),
	 * ck.getHashLoc(),m.version); long fpose = (map.getIterPos()
	 * /map.arrayLength)* Main.CHUNK_LENGTH; m.put(fpose, _ck.getBytes()); val =
	 * map.nextValue();
	 * 
	 * } m.close(); map.close(); of.delete(); Files.move(nf.toPath(),
	 * of.toPath()); m = new LongByteArrayMap(of.getPath(),(byte)1);
	 * evt.endEvent("Complete migration."); } catch(IOException e) {
	 * evt.endEvent("Unable to complete migration because : " +e.getMessage(),
	 * SDFSEvent.ERROR); throw e; } finally { try { m.close(); }catch(Exception
	 * e) { SDFSLogger.getLog().debug("unable to close map file", e); }
	 * 
	 * nf.delete(); map.hashlock.unlock(); } return m; }
	 */
}
