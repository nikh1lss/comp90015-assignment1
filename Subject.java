import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
 * The fields of this class are NOT thread-safe on their own. Each Subject
 * therefore carries its own ReentrantReadWriteLock, obtained from
 * getLock(): every read of its state must hold the read lock and every
 * mutation must hold the write lock. Keeping the lock on the subject it
 * guards makes the granularity explicit - one lock per subject, so work on
 * different subjects never contends - and means a caller cannot pick up
 * the wrong lock for the subject it is about to touch.
 */
public class Subject {

    private String subjectCode;
    private int capacity;
    private final Set<String> enrolledStudentIds;

    /** Guards capacity and enrolledStudentIds. See getLock(). */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

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

    /**
     * This subject's lock. Hold the read lock while reading capacity or the
     * enrolled set, and the write lock while changing either. Readers of one
     * subject do not block each other; a writer excludes everything else on
     * that same subject.
     */
    public ReentrantReadWriteLock getLock() {
        return lock;
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
