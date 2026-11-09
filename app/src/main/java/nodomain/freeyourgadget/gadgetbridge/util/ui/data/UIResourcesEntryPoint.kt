package nodomain.freeyourgadget.gadgetbridge.util.ui.data

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Gives classes that Hilt doesn't create access to the [UIResourcesRepository].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UIResourcesEntryPoint {
    fun uiResourcesRepository(): UIResourcesRepository

    companion object {
        fun uiResourcesRepository(context: Context): UIResourcesRepository = EntryPointAccessors
            .fromApplication(context.applicationContext, UIResourcesEntryPoint::class.java)
            .uiResourcesRepository()
    }
}
