@file:Suppress("MagicNumber")

package co.electriccoin.zcash.ui.design.theme.internal

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

interface TopAppBarColors {
    val containerColor: Color
    val navigationColor: Color
    val titleColor: Color
    val subTitleColor: Color
    val actionColor: Color

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    fun toMaterialTopAppBarColors() =
        TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
            navigationIconContentColor = navigationColor,
            titleContentColor = titleColor,
            actionIconContentColor = actionColor
        )

    fun copyColors(
        containerColor: Color = this.containerColor,
        navigationColor: Color = this.navigationColor,
        titleColor: Color = this.titleColor,
        subTitleColor: Color = this.subTitleColor,
        actionColor: Color = this.actionColor,
    ): TopAppBarColors
}

internal data class DefaultTopAppBarColors(
    override val containerColor: Color = Color.Unspecified,
    override val navigationColor: Color = Color.Unspecified,
    override val titleColor: Color = Color.Unspecified,
    override val subTitleColor: Color = Color.Unspecified,
    override val actionColor: Color = Color.Unspecified,
) : TopAppBarColors {
    override fun copyColors(
        containerColor: Color,
        navigationColor: Color,
        titleColor: Color,
        subTitleColor: Color,
        actionColor: Color
    ) = this.copy(
        containerColor = containerColor,
        navigationColor = navigationColor,
        titleColor = titleColor,
        subTitleColor = subTitleColor,
        actionColor = actionColor
    )
}
