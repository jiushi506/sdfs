package org.opendedup.sdfs.mgmt.cli;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;

import org.apache.commons.httpclient.methods.PostMethod;
import org.opendedup.collections.HashtableFullException;
import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.filestore.HashChunk;
import org.opendedup.sdfs.servers.HCServiceProxy;
import org.opendedup.util.CompressionUtils;

public class ProcessBatchGetBlocks {
	public static long runCmd(ArrayList<byte[]> hashes, String server,
			int port, String password, boolean useSSL) throws Exception,
			ClassNotFoundException, HashtableFullException {

		InputStream in = null;
		PostMethod method = null;
		try {
			SDFSLogger.getLog().debug("getting hashes [" + hashes.size() + "]");
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			ObjectOutputStream obj_out = new ObjectOutputStream(bos);
			obj_out.writeObject(hashes);
			String file = com.google.common.io.BaseEncoding.base64Url().encode(
					CompressionUtils.compressSnappy(bos.toByteArray()));
			StringBuilder sb = new StringBuilder();
			Formatter formatter = new Formatter(sb);
			formatter.format("file=%s&cmd=batchgetblocks&options=ilovemg",
					"ninja");
			method = MgmtServerConnection.connectAndPost(server, port,
					password, sb.toString(), "", file, true);
			in = method.getResponseBodyAsStream();
			SDFSLogger.getLog().debug("reading imported blocks");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			formatter.close();
			byte[] buf = new byte[32768];
			int len;
			while ((len = in.read(buf)) > 0) {
				out.write(buf, 0, len);
			}

			byte[] sh = CompressionUtils.decompressSnappy(out.toByteArray());
			ObjectInputStream obj_in = new ObjectInputStream(
					new ByteArrayInputStream(sh));
			@SuppressWarnings("unchecked")
			List<HashChunk> hck = (List<HashChunk>) obj_in.readObject();
			out.close();
			obj_in.close();
			if (hck.size() != hashes.size())
				throw new IOException("unable to import all blocks requested ["
						+ hashes.size() + "] and received [" + hck.size() + "]");
			long imsz = 0;
			for (int i = 0; i < hck.size(); i++) {
				HashChunk _hc = hck.get(i);
				imsz += _hc.getData().length;
				HCServiceProxy.writeChunk(_hc.getName(), _hc.getData(), true, null);
			}
			SDFSLogger.getLog().debug("imported " + hck.size());
			return imsz;

		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Exception e) {
				}
			}
			if (method != null)
				try {
					method.releaseConnection();
				} catch (Exception e) {
				}
		}

	}
}
