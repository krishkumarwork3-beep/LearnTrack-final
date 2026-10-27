package com.learntrack.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts text from .docx files using JDK ZipFile and DOM APIs.
 * No external dependencies - pure JDK implementation.
 */
public class SyllabusDocxReader {

    private static final String DOCUMENT_XML_PATH = "word/document.xml";
    private static final String W_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    /**
     * Extract all text from a .docx file.
     * 
     * @param file The .docx file to read
     * @return Extracted text with paragraphs separated by newlines
     * @throws IOException If file is not a valid .docx or cannot be read
     */
    public String extractText(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("File does not exist: " + file);
        }

        try (ZipFile zipFile = new ZipFile(file)) {
            ZipEntry documentEntry = zipFile.getEntry(DOCUMENT_XML_PATH);
            
            if (documentEntry == null) {
                throw new IOException("Not a valid .docx file: missing " + DOCUMENT_XML_PATH);
            }

            try (InputStream is = zipFile.getInputStream(documentEntry)) {
                return parseDocumentXml(is);
            }
        } catch (Exception e) {
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to extract text from .docx file: " + e.getMessage(), e);
        }
    }

    /**
     * Parse the word/document.xml content to extract text.
     */
    private String parseDocumentXml(InputStream is) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        
        // Secure processing: disable external entities
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(is);

        StringBuilder text = new StringBuilder();
        Element root = doc.getDocumentElement();

        // Process all paragraphs (w:p) in document order
        NodeList paragraphs = root.getElementsByTagNameNS(W_NAMESPACE, "p");
        for (int i = 0; i < paragraphs.getLength(); i++) {
            Element paragraph = (Element) paragraphs.item(i);
            String paragraphText = extractParagraphText(paragraph);
            
            if (!paragraphText.isEmpty()) {
                text.append(paragraphText).append("\n");
            }
        }

        return text.toString();
    }

    /**
     * Extract text from a paragraph element, concatenating all text runs.
     */
    private String extractParagraphText(Element paragraph) {
        StringBuilder paraText = new StringBuilder();

        // Get all text runs (w:r) in this paragraph
        NodeList runs = paragraph.getElementsByTagNameNS(W_NAMESPACE, "r");
        
        for (int i = 0; i < runs.getLength(); i++) {
            Element run = (Element) runs.item(i);
            
            // Get all text elements (w:t) in this run
            NodeList textNodes = run.getElementsByTagNameNS(W_NAMESPACE, "t");
            for (int j = 0; j < textNodes.getLength(); j++) {
                Element textElement = (Element) textNodes.item(j);
                String textContent = getTextContent(textElement);
                if (textContent != null) {
                    paraText.append(textContent);
                }
            }

            // Handle line breaks (w:br) as space
            NodeList breaks = run.getElementsByTagNameNS(W_NAMESPACE, "br");
            if (breaks.getLength() > 0) {
                paraText.append(" ");
            }

            // Handle tabs (w:tab) as space
            NodeList tabs = run.getElementsByTagNameNS(W_NAMESPACE, "tab");
            if (tabs.getLength() > 0) {
                paraText.append(" ");
            }
        }

        return paraText.toString().trim();
    }

    /**
     * Get text content from an element, handling all child text nodes.
     */
    private String getTextContent(Element element) {
        StringBuilder text = new StringBuilder();
        NodeList children = element.getChildNodes();
        
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                String content = child.getTextContent();
                if (content != null) {
                    text.append(content);
                }
            }
        }
        
        return text.toString();
    }
}
