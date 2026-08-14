import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Subject - a plain in-memory representation of one subject's data.
 *
 * This class deliberately has no behaviour: no enrol()/withdraw() methods,
 * no capacity checks, and no locking. Implementing those rules correctly
 * is the actual point of this assignment, so it is left entirely to you.
 *
 * enrolledStudentIds is a LinkedHashSet: this gives you O(1) membership
 * checks (for DUPLICATE_ENROLMENT / NOT_ENROLLED) while preserving the
 * order students were enrolled in, which is a reasonable default for the
 * enrolledStudentIds list returned by QUERY. You are free to change the
 * underlying data structure if you prefer a different one, as long as
 * your server behaves as the Functional Requirements section describes.
 *
 * This class is NOT thread-safe on its own. Any code that reads or
 * mutates a Subject concurrently from multiple threads must use your own
 * locking to do so safely - that is true whether you keep this class as
 * given or modify it.
 */
public class Subject {

    private String subjectCode;
    private int capacity;
    private final Set<String> enrolledStudentIds;

    public Subject(String subjectCode, int capacity) {
        this.subjectCode = subjectCode;
        this.capacity = capacity;
        this.enrolledStudentIds = new LinkedHashSet<>();
    }

    public Subject(String subjectCode, int capacity, Set<String> initialEnrolledStudentIds) {
        this.subjectCode = subjectCode;
        this.capacity = capacity;
        this.enrolledStudentIds = new LinkedHashSet<>(initialEnrolledStudentIds);
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public void setSubjectCode(String subjectCode) {
        this.subjectCode = subjectCode;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    /**
     * The live, mutable set of enrolled student IDs. Add to or remove from
     * this set directly (e.g. enrolledStudentIds.add(studentId)) under
     * whatever lock your server design uses to protect this subject.
     */
    public Set<String> getEnrolledStudentIds() {
        return enrolledStudentIds;
    }

    public int getEnrolledCount() {
        return enrolledStudentIds.size();
    }

    @Override
    public String toString() {
        return "Subject{" +
                "subjectCode='" + subjectCode + '\'' +
                ", capacity=" + capacity +
                ", enrolledStudentIds=" + enrolledStudentIds +
                '}';
    }
}
