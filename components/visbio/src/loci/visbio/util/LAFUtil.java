//
// LAFUtil.java
//

/*
VisBio application for visualization of multidimensional biological
image data. Copyright (C) 2002-@year@ Curtis Rueden and Abraham Sorber.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.visbio.util;

import java.awt.Dimension;
import java.util.Vector;

import javax.swing.LookAndFeel;
import javax.swing.UIManager;

import com.jgoodies.plaf.FontSizeHints;
import com.jgoodies.plaf.LookUtils;
import com.jgoodies.plaf.Options;

/**
 * LAFUtil contains useful functions relating to Look and Feel.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/visbio/src/loci/visbio/util/LAFUtil.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/visbio/src/loci/visbio/util/LAFUtil.java">SVN</a></dd></dl>
 */
public final class LAFUtil {

  // -- Constructor --

  private LAFUtil() { }

  // -- Utility methods --

  /** Initializes some look and feel parameters. */
  public static void initLookAndFeel() {
    if (System.getProperty("swing.defaultlaf") == null) {
      // use JGoodies Plastic 3D as default if no Look & Feel is set
      try {
        UIManager.setLookAndFeel(
          "com.jgoodies.plaf.plastic.Plastic3DLookAndFeel");
      }
      catch (Exception exc) { exc.printStackTrace(); }
    }

    UIManager.put(Options.USE_SYSTEM_FONTS_APP_KEY, Boolean.TRUE);
    Options.setGlobalFontSizeHints(FontSizeHints.MIXED);
    Options.setDefaultIconSize(new Dimension(18, 18));

    if (!isMacLookAndFeel()) {
      System.setProperty("apple.laf.useScreenMenuBar", "false");
    }
    UIManager.installLookAndFeel("JGoodies Windows",
      "com.jgoodies.plaf.windows.ExtWindowsLookAndFeel");
    UIManager.installLookAndFeel("JGoodies Plastic",
      "com.jgoodies.plaf.plastic.PlasticLookAndFeel");
    UIManager.installLookAndFeel("JGoodies Plastic 3D",
      "com.jgoodies.plaf.plastic.Plastic3DLookAndFeel");
    UIManager.installLookAndFeel("JGoodies Plastic XP",
      "com.jgoodies.plaf.plastic.PlasticXPLookAndFeel");
  }

  /** Gets the name and class of the current look and feel. */
  public static String[] getLookAndFeel() {
    LookAndFeel laf = UIManager.getLookAndFeel();
    return new String[] {laf.getName(), laf.getClass().getName()};
  }

  /** Gets whether the current look and feel is Mac OS X native. */
  public static boolean isMacLookAndFeel() {
    LookAndFeel laf = UIManager.getLookAndFeel();
    return laf.getClass().getName().equals("apple.laf.AquaLookAndFeel");
  }

  /** Gets whether the current look and feel is Windows native. */
  public static boolean isWindowsLookAndFeel() {
    LookAndFeel laf = UIManager.getLookAndFeel();
    return laf.getClass().getName().indexOf("Windows") >= 0;
  }

  /** Gets whether the current look and feel is GTK+. */
  public static boolean isGTKLookAndFeel() {
    LookAndFeel laf = UIManager.getLookAndFeel();
    return laf.getClass().getName().indexOf("GTK") >= 0;
  }

  /**
   * Gets whether the current look and feel is
   * one of the JGoodies Plastic series.
   */
  public static boolean isPlasticLookAndFeel() {
    LookAndFeel laf = UIManager.getLookAndFeel();
    return laf.getClass().getName().startsWith(
      "com.jgoodies.plaf.plastic.Plastic");
  }

  /**
   * Gets list of available look and feels, taking some OS-specific
   * look and feels into account.
   * @return An array dimensioned String[2][*], with String[0] being the L&F
   *         names, and String[1] being the fully qualified L&F class names.
   */
  public static String[][] getAvailableLookAndFeels() {
    UIManager.LookAndFeelInfo[] lafs = UIManager.getInstalledLookAndFeels();
    Vector v = new Vector(lafs.length);
    for (int i=0; i<lafs.length; i++) {
      String cname = lafs[i].getClassName();
      if (cname.indexOf("WindowsLookAndFeel") < 0 || LookUtils.IS_OS_WINDOWS) {
        v.add(lafs[i]);
      }
    }

    int size = v.size();
    String[][] s = new String[2][size];
    for (int i=0; i<size; i++) {
      UIManager.LookAndFeelInfo info =
        (UIManager.LookAndFeelInfo) v.elementAt(i);
      s[0][i] = info.getName();
      s[1][i] = info.getClassName();
    }
    return s;
  }

}