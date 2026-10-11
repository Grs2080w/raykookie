package br.com.kntap.raykookie.releases.adapter.out.persistence;

import br.com.kntap.raykookie.releases.application.ports.ReleaseNotices;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Último build avisado de cada app, no Postgres.
 */
@Repository
public class JdbcReleaseNotices implements ReleaseNotices {

    /** Banco. */
    private final JdbcClient jdbc;

    /**
     * Cria o repositório.
     *
     * @param jdbc cliente JDBC
     */
    public JdbcReleaseNotices(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Integer> lastBuild(String app) {
        return jdbc.sql("SELECT build FROM release_notices WHERE app = :app").param("app", app).query(Integer.class)
                .optional();
    }

    /** {@inheritDoc} */
    @Override
    public void save(String app, int build) {
        jdbc.sql("""
                INSERT INTO release_notices (app, build) VALUES (:app, :build)
                ON CONFLICT (app) DO UPDATE SET build = EXCLUDED.build, notified_at = now()""")
                .param("app", app).param("build", build).update();
    }
}
