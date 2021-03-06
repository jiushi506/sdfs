package org.opendedup.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.jpountz.lz4.LZ4Factory;

import org.apache.commons.compress.utils.IOUtils;
import org.xerial.snappy.Snappy;
import org.xerial.snappy.SnappyInputStream;
import org.xerial.snappy.SnappyOutputStream;

//import org.h2.compress.LZFInputStream;
//import org.h2.compress.LZFOutputStream;

public class CompressionUtils {

	static final LZ4Compressor lz4Compressor = LZ4Factory.nativeInstance()
			.fastCompressor();
	static final LZ4FastDecompressor lz4Decompressor = LZ4Factory
			.nativeInstance().fastDecompressor();

	public static byte[] compressZLIB(byte[] input) throws IOException {
		// Create the compressor with highest level of compression
		Deflater compressor = new Deflater();
		compressor.setLevel(Deflater.BEST_SPEED);
		// Give the compressor the data to compress
		compressor.setInput(input);
		compressor.finish();
		// Create an expandable byte array to hold the compressed data.
		// You cannot use an array that's the same size as the orginal because
		// there is no guarantee that the compressed data will be smaller than
		// the uncompressed data.
		ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);
		// Compress the data
		byte[] buf = new byte[1024];
		while (!compressor.finished()) {
			int count = compressor.deflate(buf);
			bos.write(buf, 0, count);
		}
		bos.close();
		// Get the compressed data
		byte[] compressedData = bos.toByteArray();
		return compressedData;
	}

	public static byte[] decompressZLIB(byte[] input) throws IOException {
		Inflater decompressor = new Inflater();
		decompressor.setInput(input);
		// Create an expandable byte array to hold the decompressed data
		ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);

		// Decompress the data
		byte[] buf = new byte[1024];
		while (!decompressor.finished()) {
			try {
				int count = decompressor.inflate(buf);
				bos.write(buf, 0, count);
			} catch (DataFormatException e) {
				throw new IOException(e.toString());
			}
		}
		bos.close();

		// Get the decompressed data
		byte[] decompressedData = bos.toByteArray();
		return decompressedData;
	}

	public static byte[] compressSnappy(byte[] input) throws IOException {
		return Snappy.compress(input);
	}

	public static byte[] decompressSnappy(byte[] input) throws IOException {
		return Snappy.uncompress(input);
	}

	public static byte[] compressLz4(byte[] input) throws IOException {
		return lz4Compressor.compress(input);
	}

	public static byte[] decompressLz4(byte[] input, int len)
			throws IOException {
		return lz4Decompressor.decompress(input, len);
	}
	
	public static void compressFile(File src,File dst) throws IOException {
		if(!dst.getParentFile().exists())
			dst.getParentFile().mkdirs();
		FileOutputStream fos =new FileOutputStream(dst);
        FileInputStream fis =new FileInputStream(src);
        LZ4BlockOutputStream os = new LZ4BlockOutputStream(fos,1 << 16,lz4Compressor);
        IOUtils.copy(fis, os);
        os.flush();
        os.close();
        fis.close();
	}
	
	public static void decompressFile(File src,File dst) throws IOException {
		if(!dst.getParentFile().exists())
			dst.getParentFile().mkdirs();
		FileOutputStream fos =new FileOutputStream(dst);
        FileInputStream fis =new FileInputStream(src);
        LZ4BlockInputStream is = new LZ4BlockInputStream(fis,lz4Decompressor);
        IOUtils.copy(is, fos);
        fos.flush();
        fos.close();
        fis.close();
	}
	
	public static void compressFileSnappy(File src,File dst) throws IOException {
		if(!dst.getParentFile().exists())
			dst.getParentFile().mkdirs();
		FileOutputStream fos =new FileOutputStream(dst);
        FileInputStream fis =new FileInputStream(src);
        SnappyOutputStream os = new SnappyOutputStream(fos);
        IOUtils.copy(fis, os);
        os.flush();
        os.close();
        fis.close();
        
	}
	
	public static void decompressFileSnappy(File src,File dst) throws IOException {
		if(!dst.getParentFile().exists())
			dst.getParentFile().mkdirs();
		FileOutputStream fos =new FileOutputStream(dst);
        FileInputStream fis =new FileInputStream(src);
        SnappyInputStream is = new SnappyInputStream(fis);
        IOUtils.copy(is, fos);
        fos.flush();
        fos.close();
        fis.close();
	}

	

}
