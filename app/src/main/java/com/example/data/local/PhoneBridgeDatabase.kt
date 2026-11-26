package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        OutboxEventEntity::class,
        InboxEventEntity::class,
        SyncCursorEntity::class,
        RelationshipEntity::class,
        DiagnosticLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PhoneBridgeDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun inboxDao(): InboxDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun relationshipDao(): RelationshipDao
    abstract fun diagnosticLogDao(): DiagnosticLogDao

    companion object {
        @Volatile
        private var INSTANCE: PhoneBridgeDatabase? = null

        fun getInstance(context: Context): PhoneBridgeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhoneBridgeDatabase::class.java,
                    "phonebridge_durable.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
