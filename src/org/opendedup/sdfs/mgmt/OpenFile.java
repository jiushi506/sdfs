package org.opendedup.sdfs.mgmt;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import org.opendedup.sdfs.io.DedupFileChannel;





import org.opendedup.logging.SDFSLogger;
import org.opendedup.sdfs.Main;
import org.opendedup.sdfs.filestore.MetaFileStore;
import org.opendedup.sdfs.io.MetaDataDedupFile;
import org.opendedup.util.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class OpenFile {
	
	public static HashMap<String,DedupFileChannel> OpenChannels = new HashMap<String,DedupFileChannel>();
	
	public Element getResult(String cmd, String file) throws IOException {
		try {
			Document doc = XMLUtils.getXMLDoc("open-file");
			Element root = doc.getDocumentElement();
			File f = new File(Main.volume.getPath(),file);
			MetaDataDedupFile mf = MetaFileStore.getMF(f);
			DedupFileChannel ch = mf.getDedupFile().getChannel(-33);
			root.setAttribute("fd", ch.getID());
			OpenChannels.put(ch.getID(), ch);
			return (Element) root.cloneNode(true);
		} catch (Exception e) {
			SDFSLogger.getLog().error(
					"unable to fulfill request on file " + file, e);
			throw new IOException("request to fetch attributes failed because "
					+ e.toString());
		}
	}

}

