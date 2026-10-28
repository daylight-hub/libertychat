package network.libertychat.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import network.libertychat.app.data.config.ConfigFileParser
import network.libertychat.app.data.database.dao.InterfaceDao
import network.libertychat.app.data.database.entity.InterfaceEntity
import network.libertychat.app.rns.api.model.InterfaceConfig
import network.libertychat.app.rns.api.model.toJsonString
import java.io.File
import javax.inject.Provider

/**
 * Room database for storing Reticulum network interface configurations.
 */
@Database(
    entities = [InterfaceEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class InterfaceDatabase : RoomDatabase() {
    abstract fun interfaceDao(): InterfaceDao

    /**
     * Callback to populate the database with default data on creation.
     * Imports interfaces from existing config file if present, otherwise creates default.
     */
    class Callback(
        private val context: Context,
        private val database: Provider<InterfaceDatabase>,
        private val applicationScope: CoroutineScope,
    ) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)

            // Populate database synchronously using raw SQL to avoid transaction conflicts
            // Room's onCreate() runs in a transaction, so we can't use DAO suspend methods
            // which try to create their own transactions
            populateDatabaseDirect(db)
        }

        /**
         * LCS: drop any seeded bootstrap interface from installs created
         * before LCS stopped seeding one.
         *
         * Covers upstream Columba's "Beleth RNS Hub" and the short-lived
         * "LCS Public Node" seed. The seed code only runs in [onCreate], so
         * changing it does nothing for an existing database — anyone who
         * installed an earlier build still has the row. The schema is
         * unchanged, so this is a data cleanup rather than a Room migration;
         * matching on the host as well as the name catches renamed rows.
         *
         * A user who deliberately re-adds the LCS node through the interface
         * wizard is unaffected: the wizard writes its own name for the entry,
         * and this only runs against the two seeded names.
         *
         * Cheap and idempotent: one DELETE against a table with a handful of
         * rows, on a database that is opened once per process.
         */
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            try {
                db.execSQL(
                    "DELETE FROM interfaces WHERE name IN (?, ?) OR configJson LIKE ?",
                    arrayOf<Any>("Beleth RNS Hub", "LCS Public Node", "%rns.beleth.net%"),
                )
            } catch (e: android.database.SQLException) {
                android.util.Log.w("InterfaceDatabase", "Beleth cleanup failed (non-fatal)", e)
            }
        }

        /**
         * Populate database directly using raw SQL inserts.
         * This is necessary because onCreate() runs inside a transaction,
         * and we can't use Room's DAO suspend methods which create their own transactions.
         */
        private fun populateDatabaseDirect(db: SupportSQLiteDatabase) {
            // Check if Reticulum config file exists
            val configFile = File(context.filesDir, "reticulum/config")

            if (configFile.exists()) {
                android.util.Log.i("InterfaceDatabase", "Found existing config file, importing interfaces...")
                try {
                    val interfaces = ConfigFileParser.parseConfigFile(configFile)

                    if (interfaces.isEmpty()) {
                        android.util.Log.w("InterfaceDatabase", "Config file exists but no interfaces found, creating default")
                        insertDefaultInterfacesDirect(db)
                    } else {
                        interfaces.forEachIndexed { index, config ->
                            insertInterfaceDirect(db, config, index)
                        }
                        android.util.Log.d("InterfaceDatabase", "Imported ${interfaces.size} interface(s) from config file")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("InterfaceDatabase", "Error importing config file, creating default", e)
                    insertDefaultInterfacesDirect(db)
                }
            } else {
                android.util.Log.i("InterfaceDatabase", "No config file found, creating default AutoInterface")
                insertDefaultInterfacesDirect(db)
            }
        }

        /**
         * Insert default interfaces using raw SQL.
         */
        private fun insertDefaultInterfacesDirect(db: SupportSQLiteDatabase) {
            // Insert AutoInterface
            db.execSQL(
                """
                INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                VALUES (?, ?, ?, ?, ?)
            """,
                arrayOf<Any>(
                    "Auto Discovery",
                    "AutoInterface",
                    // enabled=true
                    1,
                    """{"group_id":"","discovery_scope":"link","mode":"gateway"}""",
                    0,
                ),
            )

            // Insert AndroidBLE
            db.execSQL(
                """
                INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                VALUES (?, ?, ?, ?, ?)
            """,
                arrayOf<Any>(
                    "Bluetooth LE",
                    "AndroidBLE",
                    // enabled=true
                    1,
                    """{"device_name":"Reticulum-Android","max_connections":7,"mode":"full"}""",
                    1,
                ),
            )

            // LCS: no TCP bootstrap interface is seeded. Upstream Columba seeded
            // its Beleth RNS Hub here; LCS ships neither that nor a replacement.
            // A fresh install comes up on AutoInterface + BLE only. The LCS
            // gateway is still offered as a bootstrap interface wherever the
            // user actually chooses one — the onboarding TCP option and the
            // TCP wizard's server list both carry TcpCommunityServer.isBootstrap
            // through to the interface config. It is opt-in rather than seeded,
            // so nothing reaches the internet unprompted.
        }

        /**
         * Insert interface using raw SQL.
         */
        private fun insertInterfaceDirect(
            db: SupportSQLiteDatabase,
            config: InterfaceConfig,
            displayOrder: Int,
        ) {
            db.execSQL(
                """
                INSERT INTO interfaces (name, type, enabled, configJson, displayOrder)
                VALUES (?, ?, ?, ?, ?)
            """,
                arrayOf<Any>(
                    config.name,
                    config.typeName,
                    if (config.enabled) 1 else 0,
                    config.toJsonString(),
                    displayOrder,
                ),
            )
        }

        /**
         * Import interfaces from existing config file, or create default AutoInterface.
         * This enables migration from manual config files to database-driven configuration.
         */
        private suspend fun populateDatabase(interfaceDao: InterfaceDao) {
            // Check if Reticulum config file exists
            val configFile = File(context.filesDir, "reticulum/config")

            if (configFile.exists()) {
                // Import from existing config file
                android.util.Log.i("InterfaceDatabase", "Found existing config file, importing interfaces...")

                try {
                    val interfaces = ConfigFileParser.parseConfigFile(configFile)

                    if (interfaces.isEmpty()) {
                        android.util.Log.w("InterfaceDatabase", "Config file exists but no interfaces found, creating default")
                        createDefaultInterface(interfaceDao)
                    } else {
                        interfaces.forEachIndexed { index, config ->
                            val entity = configToEntity(config, index)
                            interfaceDao.insertInterface(entity)
                        }
                        android.util.Log.d("InterfaceDatabase", "Imported ${interfaces.size} interface(s) from config file")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("InterfaceDatabase", "Error importing config file, creating default", e)
                    createDefaultInterface(interfaceDao)
                }
            } else {
                // No config file, create default AutoInterface
                android.util.Log.i("InterfaceDatabase", "No config file found, creating default AutoInterface")
                createDefaultInterface(interfaceDao)
            }
        }

        /**
         * Create default interface configurations.
         * Creates AutoInterface, AndroidBLE, and TCPClient interfaces by default.
         */
        private suspend fun createDefaultInterface(interfaceDao: InterfaceDao) {
            val defaultAutoInterface =
                InterfaceEntity(
                    name = "Auto Discovery",
                    type = "AutoInterface",
                    enabled = true,
                    configJson =
                        """
                        {
                            "group_id": "",
                            "discovery_scope": "link",
                            "mode": "full"
                        }
                        """.trimIndent(),
                    displayOrder = 0,
                )

            val defaultBleInterface =
                InterfaceEntity(
                    name = "Bluetooth LE",
                    type = "AndroidBLE",
                    enabled = true,
                    configJson =
                        """
                        {
                            "device_name": "Reticulum-Android",
                            "max_connections": 7,
                            "mode": "roaming"
                        }
                        """.trimIndent(),
                    displayOrder = 1,
                )

            // LCS: no TCP bootstrap interface — see the raw-SQL path above.
            interfaceDao.insertInterface(defaultAutoInterface)
            interfaceDao.insertInterface(defaultBleInterface)
        }

        /**
         * Convert InterfaceConfig to InterfaceEntity.
         */
        private fun configToEntity(
            config: InterfaceConfig,
            displayOrder: Int,
        ): InterfaceEntity =
            InterfaceEntity(
                name = config.name,
                type = config.typeName,
                enabled = config.enabled,
                configJson = config.toJsonString(),
                displayOrder = displayOrder,
            )
    }
}
