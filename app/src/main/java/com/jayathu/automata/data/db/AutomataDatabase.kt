package com.jayathu.automata.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jayathu.automata.data.model.DecisionMode
import com.jayathu.automata.data.model.SavedLocation
import com.jayathu.automata.data.model.TaskConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [TaskConfig::class, SavedLocation::class],
    version = 1,
    exportSchema = false
)
abstract class AutomataDatabase : RoomDatabase() {
    abstract fun taskConfigDao(): TaskConfigDao
    abstract fun savedLocationDao(): SavedLocationDao

    companion object {
        @Volatile
        private var INSTANCE: AutomataDatabase? = null

        private val EXAMPLE_TASKS = listOf(
            TaskConfig(
                name = "Fort to Sri Dalada Maligawa",
                pickupAddress = "Fort Railway Station",
                destinationAddress = "Sri Dalada Maligawa",
                rideType = "Tuk",
                enablePickMe = true,
                enableUber = true,
                decisionMode = DecisionMode.CHEAPEST
            ),
            TaskConfig(
                name = "Fort to Beddagana Wetland Park",
                pickupAddress = "Fort Railway Station",
                destinationAddress = "Beddagana Wetland Park",
                rideType = "Bike",
                enablePickMe = true,
                enableUber = true,
                decisionMode = DecisionMode.FASTEST
            )
        )

        fun getInstance(context: Context): AutomataDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutomataDatabase::class.java,
                    "automata_db"
                ).addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            INSTANCE?.let { database ->
                                val dao = database.taskConfigDao()
                                EXAMPLE_TASKS.forEach { dao.insert(it) }
                            }
                        }
                    }
                }).build().also { INSTANCE = it }
            }
        }
    }
}
