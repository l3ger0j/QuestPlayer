package org.qp.android.helpers;

import static org.qp.android.helpers.utils.StringUtil.isNotEmptyOrBlank;

import android.net.Uri;
import android.widget.ImageView;

import androidx.core.content.res.ResourcesCompat;
import androidx.databinding.BindingAdapter;

import com.bumptech.glide.Glide;

import org.qp.android.R;

public class Bind {

    @BindingAdapter({"imageUrl"})
    public static void loadImage(ImageView view, String imageUrl) {
        if (isNotEmptyOrBlank(imageUrl)) {
            switch (view.getId()) {
                case R.id.imageBox -> Glide.with(view)
                        .load(imageUrl)
                        .into(view);
                case R.id.game_icon -> Glide.with(view)
                        .load(Uri.parse(imageUrl))
                        .centerCrop()
                        .error(R.drawable.baseline_broken_image_24)
                        .into(view);
                default -> Glide.with(view)
                        .load(imageUrl)
                        .centerCrop()
                        .into(view);
            }
        } else {
            var drawable = ResourcesCompat.getDrawable(
                    view.getContext().getResources(),
                    R.drawable.baseline_broken_image_24,
                    null
            );
            view.setImageDrawable(drawable);
        }
    }

    @BindingAdapter({"imageUri"})
    public static void loadImage(ImageView view, Uri imageUri) {
        if (isNotEmptyOrBlank(String.valueOf(imageUri))) {
            Glide.with(view)
                    .load(imageUri)
                    .centerCrop()
                    .into(view);
        } else {
            var drawable = ResourcesCompat.getDrawable(
                    view.getContext().getResources(),
                    R.drawable.baseline_broken_image_24,
                    null
            );
            view.setImageDrawable(drawable);
        }
    }

}
