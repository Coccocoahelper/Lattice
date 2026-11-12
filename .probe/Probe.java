import net.hollowcube.polar.*;
import java.nio.file.*;
import java.util.*;
public class Probe {
  public static void main(String[] a) throws Exception {
    for (String f : a) {
      PolarWorld w = PolarReader.read(Files.readAllBytes(Path.of(f)));
      System.out.println("FILE " + f + " chunks=" + w.chunks().size() + " min=" + w.minSection() + " max=" + w.maxSection() + " ver=" + w.version() + " dv=" + w.dataVersion());
      Map<String,Integer> blocks = new TreeMap<>();
      Set<String> biomes = new TreeSet<>();
      int maxY=-999,minY=999;
      for (PolarChunk c : w.chunks()) {
        for (int i=0;i<c.sections().length;i++) {
          PolarSection s = c.sections()[i];
          if (s == null) { System.out.println("NULL SECTION"); continue; }
          if (!s.isEmpty()) maxY=Math.max(maxY, w.minSection()+i);
          if (!s.isEmpty()) minY=Math.min(minY, w.minSection()+i);
          for (String p : s.blockPalette()) blocks.merge(p,1,Integer::sum);
          if (s.biomePalette()!=null) for (String p : s.biomePalette()) biomes.add(p);
        }
      }
      System.out.println("palette entries=" + blocks.size() + " biomePalette=" + biomes);
      System.out.println("nonzero sections y=" + minY + ".." + maxY);
      for (Map.Entry<String,Integer> e : blocks.entrySet()) System.out.println("B " + e.getKey());
    }
  }
}
