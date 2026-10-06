package io.github.kkwans.nasfilebrowser.data

import androidx.room.*
import kotlinx.coroutines.flow.map

enum class AppTheme { SYSTEM, LIGHT, DARK }

/** Device-wide appearance only. Credentials and account-scoped media state stay separate. */
@Entity(tableName = "app_preferences")
data class AppPreference(@PrimaryKey val id: Int = 1, val theme: AppTheme = AppTheme.SYSTEM)

@Dao interface PreferenceDao {
    @Query("SELECT * FROM app_preferences WHERE id = 1") fun observe(): kotlinx.coroutines.flow.Flow<AppPreference?>
    @Upsert suspend fun save(preference: AppPreference)
}

class AppearanceStore(database: ClientDatabase) {
    private val dao = database.preferences()
    val theme = dao.observe().map { it?.theme ?: AppTheme.SYSTEM }
    suspend fun save(theme: AppTheme) = dao.save(AppPreference(theme = theme))
}
