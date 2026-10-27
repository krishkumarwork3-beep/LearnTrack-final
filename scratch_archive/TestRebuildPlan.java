import com.learntrack.service.ExamScopeService;
import com.learntrack.util.DBConnection;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class TestRebuildPlan {
    public static void main(String[] args) {
        try {
            // Use test database copy
            DBConnection.setCustomUrl("jdbc:sqlite:learntrack_test_rebuild.db");
            
            System.out.println("BEFORE REBUILD PLAN:");
            System.out.println("===================");
            queryDatabase();
            
            System.out.println("\nRunning rebuild plan for semester 2...\n");
            ExamScopeService service = new ExamScopeService();
            ExamScopeService.RebuildPlanResult result = service.rebuildPlan(2);
            
            System.out.println("Rebuild Result:");
            System.out.println("  PLANNED deleted: " + result.getPlannedDeleted());
            System.out.println("  PENDING/MISSED deleted: " + result.getPendingDeleted());
            System.out.println("  Total deleted: " + result.getTotal());
            
            System.out.println("\nAFTER REBUILD PLAN:");
            System.out.println("===================");
            queryDatabase();
            
            System.out.println("\nUCS615 (exam 23) topic hours:");
            System.out.println("=============================");
            queryUCS615Topics();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void queryDatabase() throws Exception {
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // Count by status and type
            String sql = "SELECT status, item_type, COUNT(*) as count FROM daily_log GROUP BY status, item_type ORDER BY status, item_type";
            try (ResultSet rs = stmt.executeQuery(sql)) {
                System.out.println("Status         Type            Count");
                System.out.println("-------------------------------------");
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
    
    private static void queryUCS615Topics() throws Exception {
        try (Connection conn = DBConnection.getConnection();
             Statement stmt = conn.createStatement()) {
            
            String sql = "SELECT topic_id, hours_needed, revision_hours_needed, subtopic_id " +
                        "FROM topics WHERE exam_id = 23 ORDER BY topic_id";
            try (ResultSet rs = stmt.executeQuery(sql)) {
                System.out.println("topic_id | hours_needed | revision_hours_needed | subtopic_id");
                System.out.println("----------------------------------------------------------------");
                while (rs.next()) {
                    System.out.printf("%8d | %12.1f | %21.1f | %11d%n", 
                        rs.getInt("topic_id"), 
                        rs.getDouble("hours_needed"), 
                        rs.getDouble("revision_hours_needed"),
                        rs.getInt("subtopic_id"));
                }
            }
        }
    }
}
