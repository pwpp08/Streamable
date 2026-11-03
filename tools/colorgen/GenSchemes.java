import com.google.android.material.color.utilities.*;
import java.util.*;

/** Generates M3 colour roles for StreamFlix palettes using Material Color Utilities (HCT). */
public class GenSchemes {
  static String hex(int argb) { return String.format("#%06X", argb & 0xFFFFFF); }

  public static void main(String[] args) {
    LinkedHashMap<String, Integer> seeds = new LinkedHashMap<>();
    seeds.put("crimson", 0xFFE50914);
    seeds.put("ocean", 0xFF1E6FD9);
    seeds.put("forest", 0xFF2E7D4F);
    seeds.put("violet", 0xFF7B4DFF);
    seeds.put("sunset", 0xFFF28C28);
    MaterialDynamicColors m = new MaterialDynamicColors();
    String[] roles = {"primary","onPrimary","primaryContainer","onPrimaryContainer","inversePrimary",
      "secondary","onSecondary","secondaryContainer","onSecondaryContainer",
      "tertiary","onTertiary","tertiaryContainer","onTertiaryContainer",
      "error","onError","errorContainer","onErrorContainer",
      "background","onBackground","surface","onSurface","surfaceVariant","onSurfaceVariant",
      "surfaceDim","surfaceBright","surfaceContainerLowest","surfaceContainerLow","surfaceContainer",
      "surfaceContainerHigh","surfaceContainerHighest","inverseSurface","inverseOnSurface",
      "outline","outlineVariant","scrim","shadow",
      "primaryFixed","primaryFixedDim","onPrimaryFixed","onPrimaryFixedVariant",
      "secondaryFixed","secondaryFixedDim","onSecondaryFixed","onSecondaryFixedVariant",
      "tertiaryFixed","tertiaryFixedDim","onTertiaryFixed","onTertiaryFixedVariant"};
    for (Map.Entry<String,Integer> e : seeds.entrySet()) {
      for (boolean dark : new boolean[]{true, false}) {
        Hct src = Hct.fromInt(e.getValue());
        SchemeFidelity fid = new SchemeFidelity(src, dark, 0.0);
        // Brand-faithful primary (Fidelity) with near-neutral, cinematic surfaces:
        // secondary/neutral chroma follow Tonal Spot levels so dark surfaces read as
        // "almost black, faintly tinted" instead of the strong tint Fidelity gives a saturated seed.
        DynamicScheme s = new DynamicScheme(src, Variant.FIDELITY, dark, 0.0,
            fid.primaryPalette,
            TonalPalette.fromHueAndChroma(src.getHue(), 16.0),
            fid.tertiaryPalette,
            TonalPalette.fromHueAndChroma(src.getHue(), 4.0),
            TonalPalette.fromHueAndChroma(src.getHue(), 8.0));
        for (String r : roles) {
          try {
            DynamicColor dc = (DynamicColor) MaterialDynamicColors.class.getMethod(r).invoke(m);
            System.out.println(e.getKey()+"\t"+(dark?"dark":"light")+"\t"+r+"\t"+hex(dc.getArgb(s)));
          } catch (Exception ex) { throw new RuntimeException(r, ex); }
        }
        if (dark) {
          // Neutral tones for the AMOLED variant (pure black surface, slightly lifted containers).
          TonalPalette n = s.neutralPalette;
          int[] tones = {0, 4, 6, 10, 12, 14, 17, 20, 22, 24};
          for (int t : tones) System.out.println(e.getKey()+"\tneutral\tn"+t+"\t"+hex(n.tone(t)));
          TonalPalette nv = s.neutralVariantPalette;
          for (int t : tones) System.out.println(e.getKey()+"\tneutralvariant\tn"+t+"\t"+hex(nv.tone(t)));
        }
      }
    }
  }
}
