//
// LociConfig.java
//

/*
LOCI Plugins for ImageJ: a collection of ImageJ plugins including the
Bio-Formats Importer, Bio-Formats Exporter, Bio-Formats Macro Extensions,
Data Browser and Stack Slicer. Copyright (C) 2005-@year@ Melissa Linkert,
Curtis Rueden and Christopher Peterson.

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

package loci.plugins.config;

import ij.plugin.PlugIn;
import loci.plugins.util.WindowTools;

/**
 * An ImageJ plugin for displaying the LOCI plugins configuration dialog.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/loci-plugins/src/loci/plugins/config/LociConfig.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/loci-plugins/src/loci/plugins/config/LociConfig.java;hb=HEAD">Gitweb</a></dd></dl>
 *
 * @author Curtis Rueden ctrueden at wisc.edu
 */
public class LociConfig implements PlugIn {

  // -- PlugIn API methods --

  /** Executes the plugin. */
  public void run(String arg) {
    ConfigWindow cw = new ConfigWindow();
    WindowTools.placeWindow(cw);
    cw.setVisible(true);
  }

  // -- Main method --

  public static void main(String[] args) {
    new LociConfig().run(null);
  }

}
