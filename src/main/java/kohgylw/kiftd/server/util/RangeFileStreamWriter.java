package kohgylw.kiftd.server.util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 
 * <h2>断点式文件输出流写出工具</h2>
 * <p>
 * 该工具负责处理断点下载请求并以相应规则写出文件流（java NIO）。需要提供断点续传服务，请继承该类并调用writeRangeFileStream方法。
 * </p>
 * 
 * @author 青阳龙野(kohgylw)
 * @version 1.0
 */
public class RangeFileStreamWriter {

	/**
	 * 
	 * <h2>使用断点续传技术提供输出流</h2>
	 * <p>
	 * 处理带有断点续传参数的下载请求，并提供输出流写出。请传入相应的参数并执行该方法以开始断点传输。
	 * </p>
	 * 
	 * <pre>
	 * “愿将年岁雕琢，拂去一方萧索”
	 * </pre>
	 * 
	 * @author 青阳龙野(kohgylw)
	 * @param request
	 *            javax.servlet.http.HttpServletRequest 请求对象
	 * @param response
	 *            javax.servlet.http.HttpServletResponse 响应对象
	 * @param fo
	 *            java.io.File 需要写出的文件
	 * @param fname
	 *            java.lang.String 文件名
	 * @param contentType
	 *            java.lang.String HTTP Content-Type类型（用于控制客户端行为）
	 * @return void
	 */
	protected void writeRangeFileStream(HttpServletRequest request, HttpServletResponse response, File fo, String fname,
			String contentType) {
		long fileLength = fo.length();// 文件总大小
		long startOffset = 0; // 起始偏移量
		boolean hasEnd = false;// 请求区间是否存在结束标识
		long endOffset = 0; // 结束偏移量
		long contentLength = 0; // 响应体长度
		String rangeBytes = "";// 请求中的Range参数
		// 设置请求头，基于kiftd文件系统推荐使用application/octet-stream
		response.setContentType(contentType);
		// 设置文件信息
		try {
			response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(fname, "UTF-8"));
		} catch (UnsupportedEncodingException e) {
			// TODO 自动生成的 catch 块
			response.setHeader("Content-Disposition", "attachment; filename=" + fname);
		}
		// 设置支持断点续传功能
		response.setHeader("Accept-Ranges", "bytes");
		// 针对具备断点续传性质的请求进行解析
		if (request.getHeader("Range") != null && request.getHeader("Range").startsWith("bytes=")) {
			response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
			rangeBytes = request.getHeader("Range").replaceAll("bytes=", "");
			if (rangeBytes.endsWith("-")) {
				// 解析请求参数范围为仅有起始偏移量而无结束偏移量的情况
				startOffset = Long.parseLong(rangeBytes.substring(0, rangeBytes.indexOf('-')).trim());
				// 仅具备起始偏移量时，例如文件长为13，请求为5-，则响应体长度为8
				contentLength = fileLength - startOffset;
			} else {
				hasEnd = true;
				startOffset = Long.parseLong(rangeBytes.substring(0, rangeBytes.indexOf('-')).trim());
				endOffset = Long.parseLong(rangeBytes.substring(rangeBytes.indexOf('-') + 1).trim());
				// 具备起始偏移量与结束偏移量时，例如0-9，则响应体长度为10个字节
				contentLength = endOffset - startOffset + 1;
			}
		} else { // 从开始进行下载
			contentLength = fileLength; // 客户端要求全文下载
		}
		response.setHeader("Content-Length", "" + contentLength);// 设置请求体长度
		if (startOffset != 0) {
			// 设置Content-Range，格式为“bytes 起始偏移-结束偏移/文件的总大小”
			if (!hasEnd) {
				String contentRange = new StringBuffer("bytes ").append("" + startOffset).append("-")
						.append("" + (fileLength - 1)).append("/").append("" + fileLength).toString();
				response.setHeader("Content-Range", contentRange);
			} else {
				String contentRange = new StringBuffer("bytes ").append(rangeBytes).append("/").append("" + fileLength)
						.toString();
				response.setHeader("Content-Range", contentRange);
			}
		}
		// 写出缓冲
		ByteBuffer buffer = ByteBuffer.allocate(ConfigureReader.instance().getBuffSize());
		byte[] buf = new byte[ConfigureReader.instance().getBuffSize()];
		// NIO读取文件并写处至输出流

		try (FileChannel fc = FileChannel.open(Paths.get(fo.getAbsolutePath()), StandardOpenOption.READ)) {
			BufferedOutputStream out = new BufferedOutputStream(response.getOutputStream());
			if (!hasEnd) {
				// 无结束偏移量时，将其从起始偏移量开始写到文件整体结束，如果从头开始下载，起始偏移量为0
				fc.position(startOffset);
				int n = 0;
				while ((n = fc.read(buffer)) != -1) {
					buffer.flip();
					buffer.get(buf, 0, n);
					out.write(buf, 0, n);
					buffer.flip();
				}
			} else {
				// 有结束偏移量时，将其从起始偏移量开始写至指定偏移量结束。
				fc.position(startOffset);
				int n = 0;
				long readLength = 0;// 写出量，用于确定结束位置
				while (readLength < contentLength) {
					n = fc.read(buffer);
					buffer.flip();
					readLength += n;
					buffer.get(buf, 0, n);
					out.write(buf, 0, n);
					buffer.clear();
				}
			}
			out.flush();
			out.close();
		} catch (IOException ex) {
			// 针对任何IO异常忽略，传输失败不处理
		}
	}
}
