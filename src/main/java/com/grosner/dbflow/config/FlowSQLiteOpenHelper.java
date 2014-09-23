package com.grosner.dbflow.config;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.SparseArray;

import com.grosner.dbflow.DatabaseHelperListener;
import com.grosner.dbflow.sql.builder.QueryBuilder;
import com.grosner.dbflow.sql.migration.Migration;
import com.grosner.dbflow.structure.ModelView;
import com.grosner.dbflow.structure.TableStructure;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Author: andrewgrosner
 * Contributors: { }
 * Description:
 */
public class FlowSQLiteOpenHelper extends SQLiteOpenHelper {

    /**
     * Location where the migration files should exist.
     */
    public final static String MIGRATION_PATH = "migrations";

    private DatabaseHelperListener mListener;

    private final boolean foreignKeysSupported;

    private FlowManager mManager;

    private SparseArray<List<Migration>> mMigrations;

    public FlowSQLiteOpenHelper(FlowManager flowManager, DBConfiguration dbConfiguration) {
        super(flowManager.getContext(), dbConfiguration.mDatabaseName, null, dbConfiguration.mDatabaseVersion);
        mManager = flowManager;
        mMigrations = dbConfiguration.mMigrations;
        movePrepackagedDB(dbConfiguration.mDatabaseName);
        foreignKeysSupported = dbConfiguration.foreignKeysSupported;
    }

    /**
     * Set a listener to listen for specific DB events and perform an action before we execute this classes
     * specific methods.
     *
     * @param mListener
     */
    public void setDatabaseListener(DatabaseHelperListener mListener) {
        this.mListener = mListener;
    }

    /**
     * Copies over the prepackaged DB into the main DB then deletes the existing DB to save storage space.
     *
     * @param databaseName
     */
    public void movePrepackagedDB(String databaseName) {
        final File dbPath = mManager.getContext().getDatabasePath(databaseName);

        // If the database already exists, return
        if (dbPath.exists()) {
            return;
        }

        // Make sure we have a path to the file
        dbPath.getParentFile().mkdirs();

        // Try to copy database file
        try {
            final InputStream inputStream = mManager.getContext().getAssets().open(databaseName);
            final OutputStream output = new FileOutputStream(dbPath);

            byte[] buffer = new byte[1024];
            int length;

            while ((length = inputStream.read(buffer)) > 0) {
                output.write(buffer, 0, length);
            }

            output.flush();
            output.close();
            inputStream.close();

        } catch (IOException e) {
            FlowLog.log(FlowLog.Level.E, "Failed to open file", e);
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        if (mListener != null) {
            mListener.onOpen(db);
        }

        checkForeignKeySupport(db);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        if (mListener != null) {
            mListener.onCreate(db);
        }

        checkForeignKeySupport(db);
        executeCreations(db);
        executeMigrations(db, -1, db.getVersion());
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (mListener != null) {
            mListener.onUpgrade(db, oldVersion, newVersion);
        }

        checkForeignKeySupport(db);
        executeCreations(db);
        executeMigrations(db, oldVersion, newVersion);
    }

    /**
     * If foreign keys are supported, we turn it on the DB.
     *
     * @param database
     */
    private void checkForeignKeySupport(SQLiteDatabase database) {
        if (foreignKeysSupported) {
            database.execSQL("PRAGMA foreign_keys=ON;");
            FlowLog.log(FlowLog.Level.I, "Foreign Keys supported. Enabling foreign key features.");
        }
    }

    /**
     * This generates the SQLite commands to create the DB
     *
     * @param database
     */
    private void executeCreations(final SQLiteDatabase database) {

        FlowManager.transact(database, new Runnable() {
            @Override
            public void run() {
                Collection<TableStructure> tableStructures = mManager.getStructure().getTableStructure().values();
                for (TableStructure tableStructure : tableStructures) {
                    database.execSQL(tableStructure.getCreationQuery().getQuery());
                }

                Collection<ModelView> modelViews = mManager.getStructure().getModelViews().values();

                for (ModelView modelView : modelViews) {
                    QueryBuilder queryBuilder = new QueryBuilder()
                            .append("CREATE VIEW AS")
                            .appendSpaceSeparated(modelView.getName())
                            .append(modelView.getWhere().getQuery());
                    database.execSQL(queryBuilder.getQuery());
                }
            }
        });
    }

    private void executeMigrations(final SQLiteDatabase db, final int oldVersion, final int newVersion) {

        // will try migrations file or execute migrations from code
        try {
            final List<String> files = Arrays.asList(mManager.getContext().getAssets().list(MIGRATION_PATH));
            Collections.sort(files, new NaturalOrderComparator());

            FlowManager.transact(db, new Runnable() {
                @Override
                public void run() {
                    for (String file : files) {
                        try {
                            final int version = Integer.valueOf(file.replace(".sql", ""));

                            if (version > oldVersion && version <= newVersion) {
                                executeSqlScript(db, file);
                                FlowLog.log(FlowLog.Level.I, file + " executed succesfully.");
                            }
                        } catch (NumberFormatException e) {
                            FlowLog.log(FlowLog.Level.W, "Skipping invalidly named file: " + file, e);
                        }
                    }
                }
            });
        } catch (IOException e) {
            FlowLog.log(FlowLog.Level.E, "Failed to execute migrations.", e);
        }

        if (mMigrations != null) {
            int curVersion = oldVersion + 1;

            // execute migrations in order
            for (int i = curVersion; i <= newVersion; i++) {
                List<Migration> migrationsList = mMigrations.get(i);
                if (migrationsList != null) {
                    for (Migration migration : migrationsList) {

                        // before migration
                        migration.onPreMigrate();

                        // migrate
                        migration.migrate(db);

                        // after migration cleanup
                        migration.onPostMigrate();
                    }
                }
            }
        }
    }

    private void executeSqlScript(SQLiteDatabase db, String file) {
        try {
            final InputStream input = mManager.getContext().getAssets().open(MIGRATION_PATH + "/" + file);
            final BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            String line = null;

            while ((line = reader.readLine()) != null) {
                db.execSQL(line.replace(";", ""));
            }
        } catch (IOException e) {
            FlowLog.log(FlowLog.Level.E, "Failed to execute " + file, e);
        }
    }
}