//
// EditImageName.java
//

import loci.common.services.ServiceFactory;
import loci.formats.ImageReader;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;

/**
 * Edits the given file's image name (but does not save back to disk).
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/bio-formats/utils/EditImageName.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/bio-formats/utils/EditImageName.java;hb=HEAD">Gitweb</a></dd></dl>
 */
public class EditImageName {

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.out.println("Usage: java EditImageName file");
      return;
    }
    ImageReader reader = new ImageReader();
    // record metadata to OME-XML format
    ServiceFactory factory = new ServiceFactory();
    OMEXMLService service = factory.getInstance(OMEXMLService.class);
    IMetadata omexmlMeta = service.createOMEXMLMetadata();
    reader.setMetadataStore(omexmlMeta);
    String id = args[0];
    System.out.print("Reading metadata ");
    reader.setId(id);
    System.out.println(" [done]");

    // get image name
    String name = omexmlMeta.getImageName(0);
    System.out.println("Initial Image name = " + name);
    // change image name (reverse it)
    char[] arr = name.toCharArray();
    for (int i=0; i<arr.length/2; i++) {
      int i2 = arr.length - i - 1;
      char c = arr[i];
      char c2 = arr[i2];
      arr[i] = c2;
      arr[i2] = c;
    }
    name = new String(arr);
    // save altered name back to OME-XML structure
    omexmlMeta.setImageName(name, 0);
    System.out.println("Updated Image name = " + name);
    // output full OME-XML block
    System.out.println("Full OME-XML dump:");
    String xml = service.getOMEXML(omexmlMeta);
    System.out.println(xml);
  }

}
