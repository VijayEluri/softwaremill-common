package pl.softwaremill.common.dbtest;

import com.google.common.io.Resources;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.hibernate.cfg.Environment;
import org.hibernate.ejb.Ejb3Configuration;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;
import pl.softwaremill.common.arquillian.BetterArquillian;
import pl.softwaremill.common.cdi.persistence.EntityManagerFactoryProducer;
import pl.softwaremill.common.dbtest.util.*;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.naming.spi.NamingManager;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import javax.transaction.*;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang.StringUtils.isBlank;

/**
 * Extend this class to create tests which have a private database. The persistence classes from cdi-ext are available.
 * A template for a test is:
 * <pre>
 * public class MyTest extends AbstractDBTest {
 *  @Override
 *  public void configureEntities(Ejb3Configuration cfg) {
 *      cfg.addAnnotatedClass(MyEntity.class);
 * }
 *
 *  @Deployment
 *  public static JavaArchive createTestArchive() {
 *      return new ArchiveConfigurator() {
 *          @Override
 *          protected JavaArchive configureBeans(JavaArchive ar) {
 *              return ar.addPackage(MyBean.class.getPackage());
 *      }.createTestArchive();
 *  }
 *
 *  @Test
 *  public void mytest() { }
 *  }
 * </pre>
 *
 * @author Adam Warski (adam at warski dot org)
 */
public abstract class AbstractDBTest extends BetterArquillian {

    static {
        try {
            NamingManager.setInitialContextFactoryBuilder(new InMemoryContextFactoryBuilder());
        } catch (NamingException e) {
            throw new RuntimeException(e);
        }
    }

    protected EntityManagerFactory emf;

    private static final Logger log = Logger.getLogger(AbstractDBTest.class);

    private DbMode compatibilityMode = null;

    private TransactionManager transactionManager;

    /**
     * Additional Hibernate configuration.
     * E.g. add entities using {@code cfg.addAnnotatedClass(MyEntity.class)}
     */
    protected abstract void configureEntities(Ejb3Configuration cfg);

    /**
     * Loads test data. By default reads the content of an sql file that is named the same as the test class.
     * Override if you want to add additional test data, or suppress default test data loading.
     * <p/>
     * You can use {@link #loadURLContent(javax.persistence.EntityManager, java.net.URL)} to load the specified
     * file.
     *
     * @param em Entity manager which can be used to load data.
     */
    protected void loadTestData(EntityManager em) throws IOException {
        final String sqlFilePath = new SqlFileResolver(this.getClass()).getSqlFilePath();
        try {
            loadURLContent(em, Resources.getResource(sqlFilePath));
        } catch (IllegalArgumentException e) {
            log.info("File for initializing database (" + sqlFilePath + ") not found.");
        }
    }

    /**
     * Executes the SQL statements contained in the content of the given URL.
     */
    protected void loadURLContent(EntityManager em, URL url) throws IOException {
        String queries = Resources.toString(url, Charset.defaultCharset());

        for (String query : divideQueries(queries)) {
            try {
                em.createNativeQuery(query).executeUpdate();
            } catch (Exception e) {
                log.error("Problem running query:\n" + query + "\n", e);
            }
        }
    }

    private List<String> divideQueries(String queries) {
        List<String> queryList = new ArrayList<String>();

        for (String q : queries.split(";")) {
            if (!isBlank(q)) {
                queryList.add(q + ";");
            }
        }

        return queryList;
    }

    @BeforeSuite
    public void setupLog4J() {
        BasicConfigurator.configure();
        Logger.getRootLogger().setLevel(Level.INFO);
    }

    public AbstractDBTest() {
        this(false);
    }

    public AbstractDBTest(boolean shouldEmptyDBAfterEachTest) {
        this.shouldEmptyDBAfterEachTest = shouldEmptyDBAfterEachTest;

        try {

            Ejb3Configuration cfg = new Ejb3Configuration();

            cfg.configure(getPersistanceUnitName(), null);

            // Separate database for each test class
            cfg.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + this.getClass().getName() +
                    ";DB_CLOSE_DELAY=-1" +
                    addCompatibilityMode());

            transactionManager = DBTestJtaBootstrap.updateConfigAndCreateTM(cfg.getProperties());

            configureEntities(cfg);
            emf = cfg.buildEntityManagerFactory();

            // Setting the EMF so that it's produced correctly
            EntityManagerFactoryProducer.setStaticEntityManagerFactory(emf);

            // Loading the test data for this test
            transactionManager.begin();

            EntityManager em = emf.createEntityManager();
            em.joinTransaction();

            loadTestData(em);

            transactionManager.commit();

            // bind in JNDI for use in arq-persistance
            DataSource ds = (DataSource) cfg.getHibernateConfiguration().getProperties().get(Environment.DATASOURCE);

            InitialContext initialContext = new InitialContext();

            SchemaExport schemaExport = new SchemaExport(cfg.getHibernateConfiguration());

            initialContext.bind("/dataSource", new UnclosableDataSource(ds));
            initialContext.bind("/userTransaction", new DBTestUserTransaction(transactionManager));

            if (shouldEmptyDBAfterEachTest) {
                initialContext.bind("/schemaExporter", schemaExport);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Can be overwritten in subclass and can return null
     *
     * @return name of Hibernate XML Configuration file
     */
    protected String getPersistanceUnitName() {
        return "hibernate-test-pu";
    }

    private String addCompatibilityMode() {
        if (compatibilityMode != null) {
            return ";MODE=" + compatibilityMode.getParameterValue();
        }

        return "";
    }

    @AfterClass
    public void cleanupDatabase() {
        emf.close();
    }

    boolean tranactionHandledByContainer = true;
    boolean shouldEmptyDBAfterEachTest = false;

    @BeforeMethod
    public void beginTransaction() throws SystemException, NotSupportedException, RollbackException {
        if (transactionManager.getStatus() == Status.STATUS_NO_TRANSACTION) {
            tranactionHandledByContainer = false;

            transactionManager.begin();

            // There must be at least one sync, otherwise an exception is thrown.
            transactionManager.getTransaction().registerSynchronization(
                    new Synchronization() {
                        @Override
                        public void beforeCompletion() {
                        }

                        @Override
                        public void afterCompletion(int status) {
                        }
                    }
            );
        }
    }

    @AfterMethod
    public void commitTransaction() throws SystemException, RollbackException, HeuristicRollbackException, HeuristicMixedException {
        if (!tranactionHandledByContainer) {
            transactionManager.commit();
        }
    }

    public void setCompatibilityMode(DbMode compatibilityMode) {
        this.compatibilityMode = compatibilityMode;
    }

    public DbMode getCompatibilityMode() {
        return compatibilityMode;
    }
}
