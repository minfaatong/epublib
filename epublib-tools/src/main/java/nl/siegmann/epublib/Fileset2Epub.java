package nl.siegmann.epublib;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nl.siegmann.epublib.bookprocessor.CoverpageBookProcessor;
import nl.siegmann.epublib.bookprocessor.DefaultBookProcessorPipeline;
import nl.siegmann.epublib.bookprocessor.XslBookProcessor;
import nl.siegmann.epublib.chm.ChmParser;
import nl.siegmann.epublib.domain.Author;
import nl.siegmann.epublib.domain.Book;
import nl.siegmann.epublib.domain.Identifier;
import nl.siegmann.epublib.domain.Resource;
import nl.siegmann.epublib.epub.BookProcessor;
import nl.siegmann.epublib.epub.BookProcessorPipeline;
import nl.siegmann.epublib.epub.EpubReader;
import nl.siegmann.epublib.epub.EpubWriter;
import nl.siegmann.epublib.fileset.FilesetBookCreator;
import nl.siegmann.epublib.util.VFSUtil;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.VFS;

import org.apache.commons.cli.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Fileset2Epub {

	public static void main(String[] args) {
		Options options = new Options();
		options.addOption("in", true, "input location");
		options.addOption("out", true, "output location");
		options.addOption("input-encoding", true, "input encoding");
		options.addOption("xsl", true, "xsl file");
		options.addOption("book-processor-class", true, "book processor class");
		options.addOption("cover-image", true, "cover image");
		options.addOption("author", true, "author name");
		options.addOption("title", true, "book title");
		options.addOption("isbn", true, "book ISBN");
		options.addOption("type", true, "book type");

		CommandLineParser parser = new DefaultParser();
		CommandLine cmd = null;
		try {
			cmd = parser.parse(options, args);
		} catch (ParseException e) {
			log.error("Invalid parameter(s) passed", e);
			usage(options);
		}

		final String inputLocation = cmd.getOptionValue("in", "");
		final String outLocation = cmd.getOptionValue("out", "");
		final String xslFile = cmd.getOptionValue("xsl", "");
		final String coverImage = cmd.getOptionValue("cover-image", "");
		final String title = cmd.getOptionValue("title", "");
		final String type = cmd.getOptionValue("type", "");
		final String isbn = cmd.getOptionValue("isbn", "");
		String inputEncoding = cmd.getOptionValue("input-encoding", Constants.CHARACTER_ENCODING);
		final List<String> authorNames = Arrays.asList(cmd.getOptionValues("author"));
		final List<String> bookProcessorClassNames = Arrays.asList(cmd.getOptionValues("book-processor-class"));

		if (StringUtils.isBlank(inputLocation) || StringUtils.isBlank(outLocation)) {
			usage(options);
		}

		BookProcessorPipeline epubCleaner = new DefaultBookProcessorPipeline();
		epubCleaner.addBookProcessors(createBookProcessors(bookProcessorClassNames));
		EpubWriter epubWriter = new EpubWriter(epubCleaner);
		if (!StringUtils.isBlank(xslFile)) {
			try {
				epubCleaner.addBookProcessor(new XslBookProcessor(xslFile));
			} catch (TransformerConfigurationException e) {
				log.error("Error while parsing xls file '{}'", xslFile, e);
				usage(options);
			}
		}

		if (StringUtils.isBlank(inputEncoding)) {
			inputEncoding = Constants.CHARACTER_ENCODING;
		}

		Book book = null;
		try {
			book = parseBookFromType(type, inputLocation, inputEncoding);
		} catch (IOException | ParserConfigurationException | XPathExpressionException e) {
			log.error("Error while parsing book from input location '{}'", inputLocation, e);
			usage(options);
		}

		if (StringUtils.isNotBlank(coverImage)) {
			try {
				book.setCoverImage(new Resource(VFSUtil.resolveInputStream(coverImage), coverImage));
			} catch (IOException e) {
				log.error("Error while resolving cover image file '{}'", coverImage, e);
				usage(options);
			}
			epubCleaner.getBookProcessors().add(new CoverpageBookProcessor());
		}

		if (StringUtils.isNotBlank(title)) {
			List<String> titles = new ArrayList<>();
			titles.add(title);
			book.getMetadata().setTitles(titles);
		}

		if (StringUtils.isNotBlank(isbn)) {
			book.getMetadata().addIdentifier(new Identifier(Identifier.Scheme.ISBN, isbn));
		}

		initAuthors(authorNames, book);

		try {
			epubWriter.write(book, getOutputStreamFromPath(outLocation));
		} catch (IOException e) {
			log.error("Error while writing ebook file '{}'", outLocation, e);
			usage(options);
		}
		System.out.println("ebook conversion complete!");
	}

	private static Book parseBookFromType(String type, String inputLocation, String inputEncoding) 
		throws FileNotFoundException, FileSystemException, IOException, ParserConfigurationException, XPathExpressionException {
		switch (type) {
			case "chm":
				return ChmParser.parseChm(VFSUtil.resolveFileObject(inputLocation), inputEncoding);
			case "epub":
				return new EpubReader().readEpub(VFSUtil.resolveInputStream(inputLocation), inputEncoding);
			default:
				return FilesetBookCreator.createBookFromDirectory(VFSUtil.resolveFileObject(inputLocation),
						inputEncoding);
		}
	}

	private static OutputStream getOutputStreamFromPath(String outLocation) throws FileNotFoundException {
		try {
			return VFS.getManager().resolveFile(outLocation).getContent().getOutputStream();
		} catch (FileSystemException e) {
			return new FileOutputStream(outLocation);
		}
	}

	private static void initAuthors(List<String> authorNames, Book book) {
		if (authorNames == null || authorNames.isEmpty()) {
			return;
		}
		List<Author> authorObjects = new ArrayList<>();
		for (String authorName : authorNames) {
			String[] authorNameParts = authorName.split(",");
			Author authorObject = null;
			if (authorNameParts.length > 1) {
				authorObject = new Author(authorNameParts[1], authorNameParts[0]);
			} else if (authorNameParts.length > 0) {
				authorObject = new Author(authorNameParts[0]);
			}
			authorObjects.add(authorObject);
		}
		book.getMetadata().setAuthors(authorObjects);
	}

	private static List<BookProcessor> createBookProcessors(List<String> bookProcessorNames) {
		List<BookProcessor> result = new ArrayList<>(bookProcessorNames.size());
		for (String bookProcessorName : bookProcessorNames) {
			BookProcessor bookProcessor = null;
			try {
				bookProcessor = (BookProcessor) Class.forName(bookProcessorName).getDeclaredConstructor().newInstance();
				result.add(bookProcessor);
			} catch (Exception e) {
				log.error("Error while initializing ebook processor '{}'", bookProcessorName, e);
				e.printStackTrace();
			}
		}
		return result;
	}

	private static void usage(Options options) {
		HelpFormatter formatter = new HelpFormatter();
		formatter.printHelp("Fileset2Epub", options);
		System.exit(1);
	}
}