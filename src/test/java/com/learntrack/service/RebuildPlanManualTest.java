package com.learntrack.service;

import com.learntrack.util.DBConnection;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Integration test to run rebuild plan on actual database copy and report results.
 */
public class RebuildPlanManualTest {
    
    @Test
    public void testRebuildPlanOnDatabaseCopy() throws Exception {
        // Copy actual database to test copy
        File sourceDb = new File("learntrack.db");
        File testDb = new File("learntrack_rebuild_test.db");
        
        if (!sourceDb.exists()) {
            System.out.println("Source database not found, skipping test");
            return;
        }
        
        Files.copy(sourceDb.toPath(), testDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
        
        try {
            // Point to test database
            DBConnection.setCustomUrl("jdbc:sqlite:learntrack_rebuild_test.db");
            
            System.out.println("\n========================================");
            System.out.println("BEFORE REBUILD PLAN:");
            System.out.println("========================================");
            queryDatabase();
            queryUCS615Topics();
            
            System.out.println("\n========================================");
            System.out.println("Running rebuild plan for semester 2...");
            System.out.println("========================================");
            ExamScopeService service = new ExamScopeService();
            ExamScopeService.RebuildPlanResult result = service.rebuildPlan(2);
            
            System.out.println("\nRebuild Result:");
            System.out.println("  PLANNED deleted: " + result.getPlannedDeleted());
            System.out.println("  PENDING/MISSED deleted: " + result.getPendingDeleted());
            System.out.println("  Total deleted: " + result.getTotal());
            
            System.out.println("\n========================================");
            System.out.println("AFTER REBUILD PLAN:");
            System.out.println("========================================");
            queryDatabase();
            queryUCS615Topics();
            
        } finally {
            DBConnection.resetToDefaultUrl();
            testDb.delete();
        }
    }
    
    private void queryDatabase() throws Exception {
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Count by status and type
            String sql = "SELECT status, item_type, COUNT(*) as count FROM daily_log GROUP BY status, item_type ORDER BY status, item_type";
            System.out.println("\nDaily Log Counts:");
            System.out.println("Status         Type            Count");
            System.out.println("-------------------------------------");
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    System.out.printf("%-14s %-15s %d%n", 
                        rs.getString("status"), 
                        rs.getString("item_type"), 
                        rs.getInt("count"));
                }
            }
            
            // Total count
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as total FROM daily_log")) {
                if (rs.next()) {
                    System.out.println("-------------------------------------");
                    System.out.println("Total: " + rs.getInt("total"));
                }
            }
        }
    }
    
    private void queryUCS615Topics() throws Exception {
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            
            String sql = "SELECT topic_id, hours_needed, revision_hours_needed, subtopic_id " +
                        "FROM topics WHERE exam_id = 23 ORDER BY topic_id LIMIT 5";
            System.out.println("\nUCS615 (exam 23) topic hours (first 5):");
            System.out.println("topic_id | hours_needed | revision_hours_needed | subtopic_id");
            System.out.println("----------------------------------------------------------------");
            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    System.out.printf("%8d | %12.1f | %21.1f | %11d%n", 
                        rs.getInt("topic_id"), 
                        rs.getDouble("hours_needed"), 
                        rs.getDouble("revision_hours_needed"),
                        rs.getInt("subtopic_id"));
                }
            }
            
            // Check subtopic hours for comparison
            sql = "SELECT hours_to_cover, hours_to_revise FROM syllabus_subtopics WHERE subtopic_id = 371";
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    System.out.printf("Subtopic 371: hours_to_cover=%.1f, hours_to_revise=%.1f%n", 
                        rs.getDouble("hours_to_cover"), 
                        rs.getDouble("hours_to_revise"));
                }
            }
        }
    }
}

