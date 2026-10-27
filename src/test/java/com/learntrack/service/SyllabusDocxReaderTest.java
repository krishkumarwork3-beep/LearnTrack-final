package com.learntrack.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SyllabusDocxReader.
 */
public class SyllabusDocxReaderTest {

    private final SyllabusDocxReader reader = new SyllabusDocxReader();

    @Test
    void testExtractTextFromValidDocx(@TempDir Path tempDir) throws IOException {
        // Create a minimal valid .docx file
        File docxFile = tempDir.resolve("test.docx").toFile();
        
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:r>
                        <w:t>First paragraph.</w:t>
                      </w:r>
                    </w:p>
                    <w:p>
                      <w:r>
                        <w:t>Second </w:t>
                      </w:r>
                      <w:r>
                        <w:t>paragraph split across runs.</w:t>
                      </w:r>
                    </w:p>
                    <w:p>
                      <w:r>
                        <w:t>Third paragraph with</w:t>
                        <w:br/>
                        <w:t>line break.</w:t>
                      </w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(docxFile))) {
            // Add word/document.xml
            ZipEntry entry = new ZipEntry("word/document.xml");
            zos.putNextEntry(entry);
            zos.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        String extractedText = reader.extractText(docxFile);
        
        assertNotNull(extractedText);
        assertTrue(extractedText.contains("First paragraph"), "Should contain first paragraph");
        assertTrue(extractedText.contains("Second paragraph split across runs"), "Should join text from multiple runs");
        assertTrue(extractedText.contains("Third paragraph"), "Should contain third paragraph");
        assertTrue(extractedText.contains("line break"), "Should handle line breaks");
        
        // Check that paragraphs are separated by newlines
        String[] lines = extractedText.split("\n");
        assertTrue(lines.length >= 3, "Should have at least 3 lines");
    }

    @Test
    void testExtractTextWithTabs(@TempDir Path tempDir) throws IOException {
        File docxFile = tempDir.resolve("test_tabs.docx").toFile();
        
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p>
                      <w:r>
                        <w:t>Text with</w:t>
                        <w:tab/>
                        <w:t>tab character.</w:t>
                      </w:r>
                    </w:p>
                  </w:body>
                </w:document>
                """;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(docxFile))) {
            ZipEntry entry = new ZipEntry("word/document.xml");
            zos.putNextEntry(entry);
            zos.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        String extractedText = reader.extractText(docxFile);
        
        assertTrue(extractedText.contains("Text with"));
        assertTrue(extractedText.contains("tab character"));
    }

    @Test
    void testInvalidDocxFileThrowsException(@TempDir Path tempDir) throws IOException {
        // Create a file that's not a valid .docx (missing word/document.xml)
        File invalidFile = tempDir.resolve("invalid.docx").toFile();
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(invalidFile))) {
            ZipEntry entry = new ZipEntry("some_other_file.xml");
            zos.putNextEntry(entry);
            zos.write("<root>Not a docx</root>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        IOException exception = assertThrows(IOException.class, () -> {
            reader.extractText(invalidFile);
        });
        
        assertTrue(exception.getMessage().contains("valid .docx") || 
                   exception.getMessage().contains("word/document.xml"));
    }

    @Test
    void testNonExistentFileThrowsException() {
        File nonExistent = new File("nonexistent_file.docx");
        
        IOException exception = assertThrows(IOException.class, () -> {
            reader.extractText(nonExistent);
        });
        
        assertTrue(exception.getMessage().contains("does not exist"));
    }

    @Test
    void testExtractTextFromEmptyDocument(@TempDir Path tempDir) throws IOException {
        File docxFile = tempDir.resolve("empty.docx").toFile();
        
        String documentXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                  </w:body>
                </w:document>
                """;

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(docxFile))) {
            ZipEntry entry = new ZipEntry("word/document.xml");
            zos.putNextEntry(entry);
            zos.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        String extractedText = reader.extractText(docxFile);
        
        assertNotNull(extractedText);
        assertTrue(extractedText.trim().isEmpty() || extractedText.isBlank());
    }
}
