package br.com.kntap.raykookie.feedback.adapter.out.persistence;

import br.com.kntap.raykookie.feedback.application.ports.FeedbackStore;
import br.com.kntap.raykookie.kernel.ids.CustomerId;
import br.com.kntap.raykookie.kernel.ids.OwnerId;
import br.com.kntap.raykookie.persistence.NodeRows;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feedback com o SQL de {@code backend/lib/feedback.ts}.
 */
@Repository
public class JdbcFeedbackStore implements FeedbackStore {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcFeedbackStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public long insert(OwnerId owner, CustomerId customer, long rating, String message, List<Photo> photos) {
        long id = jdbc.sql("""
                INSERT INTO feedback (owner_id, customer_id, rating, message) VALUES (:owner, :customer, :rating, :msg)
                RETURNING id""")
                .param("owner", owner.value()).param("customer", customer.value()).param("rating", rating)
                .param("msg", message).query(Long.class).single();
        for (int i = 0; i < photos.size(); i++) {
            jdbc.sql("""
                    INSERT INTO feedback_photos (feedback_id, idx, content_type, data, size)
                    VALUES (:id, :idx, :type, :data, :size)""")
                    .param("id", id).param("idx", i + 1).param("type", photos.get(i).contentType())
                    .param("data", photos.get(i).data()).param("size", photos.get(i).data().length).update();
        }
        return id;
    }

    /** {@inheritDoc} */
    @Override
    public List<Map<String, Object>> list(OwnerId owner, int limit) {
        return jdbc.sql("""
                SELECT f.id, f.rating, f.message, f.created_at, c.name AS customer_name, c.email AS customer_email,
                       (SELECT count(*)::int FROM feedback_photos p WHERE p.feedback_id = f.id) AS photos
                  FROM feedback f JOIN customers c ON c.id = f.customer_id
                 WHERE f.owner_id = :owner
                 ORDER BY f.created_at DESC LIMIT :limit""")
                .param("owner", owner.value()).param("limit", limit).query(NodeRows.ROW).list();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Photo> ownerPhoto(long feedbackId, int n, OwnerId owner) {
        return jdbc.sql("""
                SELECT p.content_type, p.data FROM feedback_photos p JOIN feedback f ON f.id = p.feedback_id
                 WHERE f.id = :id AND p.idx = :n AND f.owner_id = :owner""")
                .param("id", feedbackId).param("n", n).param("owner", owner.value())
                .query((rs, row) -> new Photo(rs.getString(1), rs.getBytes(2))).optional();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Photo> customerPhoto(long feedbackId, int n, CustomerId customer) {
        return jdbc.sql("""
                SELECT p.content_type, p.data FROM feedback_photos p JOIN feedback f ON f.id = p.feedback_id
                 WHERE f.id = :id AND p.idx = :n AND f.customer_id = :customer""")
                .param("id", feedbackId).param("n", n).param("customer", customer.value())
                .query((rs, row) -> new Photo(rs.getString(1), rs.getBytes(2))).optional();
    }
}
