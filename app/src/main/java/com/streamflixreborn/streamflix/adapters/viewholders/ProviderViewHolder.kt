package com.streamflixreborn.streamflix.adapters.viewholders

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.PictureDrawable
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.material.color.MaterialColors
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemProviderMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemProviderTvBinding
import com.streamflixreborn.streamflix.models.Provider
import com.streamflixreborn.streamflix.ui.motion.FocusScaleAnimator
import com.streamflixreborn.streamflix.ui.motion.PressScale
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.health.HealthStore
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.toActivity
import java.util.Locale

class ProviderViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private lateinit var provider: Provider

    init {
        when (_binding) {
            is ItemProviderMobileBinding -> PressScale.attach(_binding.root)
            // Card stroke becomes the focus ring; the card springs up on focus.
            is ItemProviderTvBinding -> FocusScaleAnimator.attach(_binding.root)
        }
    }

    fun bind(provider: Provider) {
        this.provider = provider

        when (_binding) {
            is ItemProviderMobileBinding -> displayMobileItem(_binding)
            is ItemProviderTvBinding -> displayTvItem(_binding)
        }
    }


    private fun displayMobileItem(binding: ItemProviderMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                UserPreferences.currentProvider = provider.provider
                context.toActivity()?.apply {
                    startActivity(
                        Intent(this, this::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    finish()
                }
            }
            setOnLongClickListener {
                toggleFavorite(provider)
                binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE
                true
            }
        }
        
        binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE

        loadProviderLogo(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name

        binding.tvProviderLanguage.text = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
        bindHealth(binding.vProviderHealth)
    }

    private fun displayTvItem(binding: ItemProviderTvBinding) {
        binding.root.apply {
            setOnClickListener {
                UserPreferences.currentProvider = provider.provider
                context.toActivity()?.apply {
                    startActivity(
                        Intent(this, this::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                    )
                    finish()
                }
            }
            setOnLongClickListener {
                toggleFavorite(provider)
                binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE
                true
            }
        }
        
        binding.ivProviderFavorite.visibility = if (provider.isFavorite) android.view.View.VISIBLE else android.view.View.GONE

        loadProviderLogo(binding.ivProviderLogo)

        binding.tvProviderName.text = provider.name

        binding.tvProviderLanguage.text = Locale.forLanguageTag(provider.language)
            .let { it.getDisplayLanguage(it) }
            .replaceFirstChar { it.titlecase() }
        bindHealth(binding.vProviderHealth)
    }

    /**
     * Health dot from the last health check (Settings > Sources > Health): primary-harmonised green
     * when a server worked, error when every checked server failed, hidden when never checked.
     */
    private fun bindHealth(dot: View) {
        val now = System.currentTimeMillis()
        val results = HealthStore.latest().filter {
            it.provider == provider.name && !it.isUnknown && now - it.checkedAt <= HealthStore.MAX_AGE_MS
        }
        if (results.isEmpty()) {
            dot.visibility = View.GONE
            dot.contentDescription = null
            return
        }
        val ok = results.any { it.ok }
        val color = if (ok) {
            MaterialColors.harmonizeWithPrimary(context, ContextCompat.getColor(context, R.color.health_status_ok))
        } else {
            ThemeManager.color(context, androidx.appcompat.R.attr.colorError)
        }
        dot.backgroundTintList = ColorStateList.valueOf(color)
        dot.visibility = View.VISIBLE
        dot.contentDescription = context.getString(if (ok) R.string.ui_misc_provider_health_ok else R.string.ui_misc_provider_health_failed)
    }

    private fun loadProviderLogo(imageView: ImageView) {
        val logo = provider.logo.takeIf { it.isNotEmpty() }
        val isSvg = logo?.substringBefore("?")?.endsWith(".svg", ignoreCase = true) == true

        if (isSvg) {
            imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            Glide.with(context)
                .`as`(PictureDrawable::class.java)
                .load(logo)
                .error(R.drawable.ic_provider_default_logo)
                .into(imageView)
        } else {
            imageView.setLayerType(View.LAYER_TYPE_NONE, null)
            Glide.with(context)
                .load(logo ?: R.drawable.ic_provider_default_logo)
                .error(R.drawable.ic_provider_default_logo)
                .fitCenter()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(imageView)
        }
    }
    
    private fun toggleFavorite(provider: Provider) {
        provider.isFavorite = !provider.isFavorite
        val favorites = UserPreferences.favoriteProviders.toMutableSet()
        if (provider.isFavorite) {
            favorites.add(provider.name)
        } else {
            favorites.remove(provider.name)
        }
        UserPreferences.favoriteProviders = favorites
    }
}
