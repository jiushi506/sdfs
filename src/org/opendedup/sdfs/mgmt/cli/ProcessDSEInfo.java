package org.opendedup.sdfs.mgmt.cli;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Formatter;
import java.util.Locale;

import org.opendedup.util.StorageUnit;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class ProcessDSEInfo {
	public static void runCmd() {
		try {
			StringBuilder sb = new StringBuilder();
			Formatter formatter = new Formatter(sb);
			formatter.format("file=%s&cmd=dse-info", "null");
			Document doc = MgmtServerConnection.getResponse(sb.toString());
			Element root = doc.getDocumentElement();
			formatter.close();
			if (root.getAttribute("status").equals("failed"))
				System.out.println(root.getAttribute("msg"));
			else {
				Element dse = (Element) root.getElementsByTagName("dse")
						.item(0);
				long maxSz = Long.parseLong(dse.getAttribute("max-size"));
				long maxCacheSz = Long.parseLong(dse.getAttribute("max-cache-size"));
				long cacheSz = Long.parseLong(dse.getAttribute("cache-size"));
				long rsp = Long.parseLong(dse.getAttribute("read-speed"))*1024;
				long wsp = Long.parseLong(dse.getAttribute("write-speed"))*1024;
				long currentSz = Long.parseLong(dse
						.getAttribute("current-size"));
				long compressedSz = Long.parseLong(dse
						.getAttribute("compressed-size"));
				long freeBlocks = Long.parseLong(dse
						.getAttribute("free-blocks"));
				int pageSize = Integer.parseInt(dse.getAttribute("page-size"));
				int port = Integer.parseInt(dse.getAttribute("listen-port"));
				String host = dse.getAttribute("listen-hostname");
				double pFull = 0.00;
				if (currentSz > 0) {
					pFull = (((double) currentSz / (double) maxSz) * 100);
					DecimalFormat twoDForm = (DecimalFormat)NumberFormat.getNumberInstance(Locale.US);
					twoDForm.applyPattern("#.##");
					pFull = Double.valueOf(twoDForm.format(pFull));
				}
				System.out.printf("DSE Max Size : %s\n", StorageUnit.of(maxSz)
						.format(maxSz));
				System.out.printf("DSE Current Size : %s\n",
						StorageUnit.of(currentSz).format(currentSz));
				System.out.printf("DSE Compressed Size : %s\n",
						StorageUnit.of(compressedSz).format(compressedSz));
				System.out.printf("DSE Percent Full : %s%%\n", pFull);
				System.out.printf("DSE Page Size : %s\n", pageSize);
				System.out.printf("DSE Blocks Available for Reuse : %s\n",
						freeBlocks);
				System.out.printf("DSE Listen Port : %s\n", port);
				System.out.printf("DSE Listen Host : %s\n", host);
				System.out.printf("DSE Listen SSL : %s\n",
						dse.getAttribute("listen-encrypted"));
				System.out.printf("DSE Current Cache Size : %s\n",
						StorageUnit.of(cacheSz)
						.format(cacheSz));
				System.out.printf("DSE Max Cache Size : %s\n",
						StorageUnit.of(maxCacheSz)
						.format(maxCacheSz));
				System.out.printf("Trottled Read Speed : %s/s\n",
						StorageUnit.of(rsp)
						.format(rsp));
				System.out.printf("Trottled Write Speed : %s/s\n",
						StorageUnit.of(wsp)
						.format(wsp));
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	public static void main(String[] args) {
		runCmd();
	}

}
