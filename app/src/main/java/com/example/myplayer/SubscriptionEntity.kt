package com.example.myplayer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val avatar: String,
    val subscribers: String,
    val subscribedAt: Long
)
