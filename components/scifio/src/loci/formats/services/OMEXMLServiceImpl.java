//
// OMEXMLServiceImpl.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

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

package loci.formats.services;

import java.io.IOException;
import java.util.Hashtable;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import loci.common.services.AbstractService;
import loci.common.services.ServiceException;
import loci.common.xml.XMLTools;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataConverter;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.meta.MetadataStore;
import loci.formats.ome.OMEXMLMetadata;
import loci.formats.ome.OMEXMLMetadataImpl;
import ome.xml.OMEXMLFactory;
import ome.xml.model.BinData;
import ome.xml.model.Channel;
import ome.xml.model.Image;
import ome.xml.model.MetadataOnly;
import ome.xml.model.OME;
import ome.xml.model.OMEModel;
import ome.xml.model.OMEModelImpl;
import ome.xml.model.OMEModelObject;
import ome.xml.model.Pixels;
import ome.xml.model.StructuredAnnotations;
import ome.xml.model.XMLAnnotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://trac.openmicroscopy.org.uk/ome/browser/bioformats.git/components/bio-formats/src/loci/formats/services/OMEXMLServiceImpl.java">Trac</a>,
 * <a href="http://git.openmicroscopy.org/?p=bioformats.git;a=blob;f=components/bio-formats/src/loci/formats/services/OMEXMLServiceImpl.java;hb=HEAD">Gitweb</a></dd></dl>
 *
 * @author callan
 */
public class OMEXMLServiceImpl extends AbstractService implements OMEXMLService
{

  public static final String NO_OME_XML_MSG =
    "ome-xml.jar is required to read OME-TIFF files.  " +
    "Please download it from " + FormatTools.URL_BIO_FORMATS_LIBRARIES;

  /** Logger for this class. */
  private static final Logger LOGGER =
    LoggerFactory.getLogger(OMEXMLService.class);

  /** Reordering stylesheet. */
  private static final Templates reorderXSLT =
    XMLTools.getStylesheet("/loci/formats/meta/reorder-2008-09.xsl",
    OMEXMLServiceImpl.class);

  /** Stylesheets for updating from previous schema releases. */
  private static final Templates UPDATE_2003FC =
    XMLTools.getStylesheet("/loci/formats/meta/2003-FC-to-2008-09.xsl",
    OMEXMLServiceImpl.class);
  private static final Templates UPDATE_2006LO =
    XMLTools.getStylesheet("/loci/formats/meta/2006-LO-to-2008-09.xsl",
    OMEXMLServiceImpl.class);
  private static final Templates UPDATE_200706 =
    XMLTools.getStylesheet("/loci/formats/meta/2007-06-to-2008-09.xsl",
    OMEXMLServiceImpl.class);
  private static final Templates UPDATE_200802 =
    XMLTools.getStylesheet("/loci/formats/meta/2008-02-to-2008-09.xsl",
    OMEXMLServiceImpl.class);
  private static final Templates UPDATE_200809 =
    XMLTools.getStylesheet("/loci/formats/meta/2008-09-to-2009-09.xsl",
    OMEXMLServiceImpl.class);
  private static final Templates UPDATE_200909 =
    XMLTools.getStylesheet("/loci/formats/meta/2009-09-to-2010-04.xsl",
    OMEXMLServiceImpl.class);
  private static final Templates UPDATE_201004 =
    XMLTools.getStylesheet("/loci/formats/meta/2010-04-to-2010-06.xsl",
    OMEXMLServiceImpl.class);
  private static final Templates UPDATE_201006 =
    XMLTools.getStylesheet("/loci/formats/meta/2010-06-to-2011-06.xsl",
    OMEXMLServiceImpl.class);

  private static final String SCHEMA_PATH =
    "http://www.openmicroscopy.org/Schemas/OME/";

  /**
   * Default constructor.
   */
  public OMEXMLServiceImpl() {
    checkClassDependency(ome.xml.model.OMEModelObject.class);
  }

  /** @see OMEXMLService#getLatestVersion() */
  public String getLatestVersion() {
    return OMEXMLFactory.LATEST_VERSION;
  }

  /** @see OMEXMLService#transformToLatestVersion(String) */
  public String transformToLatestVersion(String xml) throws ServiceException {
    String version = getOMEXMLVersion(xml);
    if (version.equals(getLatestVersion())) return xml;
    LOGGER.debug("Attempting to update XML with version: {}", version);
    LOGGER.trace("Initial dump: {}", xml);

    String transformed = null;
    try {
      if (version.equals("2003-FC")) {
        xml = verifyOMENamespace(xml);
        LOGGER.debug("Running UPDATE_2003FC stylesheet.");
        transformed = XMLTools.transformXML(xml, UPDATE_2003FC);
      }
      else if (version.equals("2006-LO")) {
        xml = verifyOMENamespace(xml);
        LOGGER.debug("Running UPDATE_2006LO stylesheet.");
        transformed = XMLTools.transformXML(xml, UPDATE_2006LO);
      }
      else if (version.equals("2007-06")) {
        xml = verifyOMENamespace(xml);
        LOGGER.debug("Running UPDATE_200706 stylesheet.");
        transformed = XMLTools.transformXML(xml, UPDATE_200706);
      }
      else if (version.equals("2008-02")) {
        xml = verifyOMENamespace(xml);
        LOGGER.debug("Running UPDATE_200802 stylesheet.");
        transformed = XMLTools.transformXML(xml, UPDATE_200802);
      }
      else transformed = xml;
      LOGGER.debug("XML updated to at least 2008-09");
      LOGGER.trace("At least 2008-09 dump: {}", transformed);

      if (!version.equals("2009-09") && !version.equals("2010-04") &&
        !version.equals("2010-06"))
      {
        transformed = verifyOMENamespace(transformed);
        LOGGER.debug("Running UPDATE_200809 stylesheet.");
        transformed = XMLTools.transformXML(transformed, UPDATE_200809);
      }
      LOGGER.debug("XML updated to at least 2009-09");
      LOGGER.trace("At least 2009-09 dump: {}", transformed);
      if (!version.equals("2010-04") && !version.equals("2010-06")) {
        transformed = verifyOMENamespace(transformed);
        LOGGER.debug("Running UPDATE_200909 stylesheet.");
        transformed = XMLTools.transformXML(transformed, UPDATE_200909);
      }
      else transformed = xml;
      LOGGER.debug("XML updated to at least 2010-04");
      LOGGER.trace("At least 2010-04 dump: {}", transformed);

      if (!version.equals("2010-06")) {
        transformed = verifyOMENamespace(transformed);
        LOGGER.debug("Running UPDATE_201004 stylesheet.");
        transformed = XMLTools.transformXML(transformed, UPDATE_201004);
      }
      else transformed = xml;
      LOGGER.debug("XML updated to at least 2010-06");

      transformed = verifyOMENamespace(transformed);
      LOGGER.debug("Running UPDATE_201006 stylesheet.");
      transformed = XMLTools.transformXML(transformed, UPDATE_201006);
      LOGGER.debug("XML updated to at least 2011-06");

      // fix namespaces
      transformed = transformed.replaceAll("<ns.*?:", "<");
      transformed = transformed.replaceAll("xmlns:ns.*?=", "xmlns:OME=");
      transformed = transformed.replaceAll("</ns.*?:", "</");
      LOGGER.trace("Transformed XML dump: {}", transformed);
      return transformed;
    }
    catch (IOException e) {
      LOGGER.warn("Could not transform version " + version + " OME-XML.");
    }
    return null;
  }

  /** @see OMEXMLService#createOMEXMLMetadata() */
  public OMEXMLMetadata createOMEXMLMetadata() throws ServiceException {
    return createOMEXMLMetadata(null);
  }

  /** @see OMEXMLService#createOMEXMLMetadata(java.lang.String) */
  public OMEXMLMetadata createOMEXMLMetadata(String xml)
    throws ServiceException {
    return createOMEXMLMetadata(xml, null);
  }

  /**
   * @see OMEXMLService#createOMEXMLMetadata(java.lang.String, java.lang.String)
   */
  public OMEXMLMetadata createOMEXMLMetadata(String xml, String version)
    throws ServiceException {
    if (xml != null) {
      xml = XMLTools.sanitizeXML(xml);
    }
    OMEModelObject ome =
      xml == null ? null : createRoot(transformToLatestVersion(xml));

    OMEXMLMetadata meta = new OMEXMLMetadataImpl();
    if (ome != null) meta.setRoot(ome);
    return meta;
  }

  /** @see OMEXMLService#createOMEXMLRoot(java.lang.String) */
  public Object createOMEXMLRoot(String xml) throws ServiceException {
    return createRoot(transformToLatestVersion(xml));
  }

  /** @see OMEXMLService#isOMEXMLMetadata(java.lang.Object) */
  public boolean isOMEXMLMetadata(Object o) {
    return o instanceof OMEXMLMetadata;
  }

  /** @see OMEXMLService#isOMEXMLRoot(java.lang.Object) */
  public boolean isOMEXMLRoot(Object o) {
    return o instanceof OMEModelObject;
  }

  /**
   * Constructs an OME root node. <b>NOTE:</b> This method is mostly here to
   * ensure type safety of return values as instances of service dependency
   * classes should not leak out of the interface.
   * @param xml String of XML to create the root node from.
   * @return An ome.xml.model.OMEModelObject subclass root node.
   * @throws IOException If there is an error reading from the string.
   * @throws SAXException If there is an error parsing the XML.
   * @throws ParserConfigurationException If there is an error preparing the
   * parsing infrastructure.
   */
  private OMEModelObject createRoot(String xml) throws ServiceException {
    try {
      OMEModel model = new OMEModelImpl();
      OME ome = new OME(XMLTools.parseDOM(xml).getDocumentElement(), model);
      model.resolveReferences();
      return ome;
    }
    catch (Exception e) {
      throw new ServiceException(e);
    }
  }

  /** @see OMEXMLService#getOMEXMLVersion(java.lang.Object) */
  public String getOMEXMLVersion(Object o) {
    if (o == null) return null;
    if (o instanceof OMEXMLMetadata || o instanceof OMEModelObject) {
      return OMEXMLFactory.LATEST_VERSION;
    }
    else if (o instanceof String) {
      String xml = (String) o;
      try {
        Element e = XMLTools.parseDOM(xml).getDocumentElement();
        String namespace = e.getAttribute("xmlns");
        if (namespace == null || namespace.equals("")) {
          namespace = e.getAttribute("xmlns:ome");
        }

        return namespace.endsWith("ome.xsd") ? "2003-FC" :
          namespace.substring(namespace.lastIndexOf("/") + 1);
      }
      catch (ParserConfigurationException pce) { }
      catch (SAXException se) { }
      catch (IOException ioe) { }
    }
    return null;
  }

  /** @see OMEXMLService#getOMEMetadata(loci.formats.meta.MetadataRetrieve) */
  public OMEXMLMetadata getOMEMetadata(MetadataRetrieve src)
    throws ServiceException {
    // check if the metadata is already an OME-XML metadata object
    if (src instanceof OMEXMLMetadata) return (OMEXMLMetadata) src;

    // populate a new OME-XML metadata object with metadata
    // converted from the non-OME-XML metadata object
    OMEXMLMetadata omexmlMeta = createOMEXMLMetadata();
    convertMetadata(src, omexmlMeta);
    return omexmlMeta;
  }

  /** @see OMEXMLService#getOMEXML(loci.formats.meta.MetadataRetrieve) */
  public String getOMEXML(MetadataRetrieve src) throws ServiceException {
    OMEXMLMetadata omexmlMeta = getOMEMetadata(src);
    String xml = omexmlMeta.dumpXML();

    // make sure that the namespace has been set correctly

    // convert XML string to DOM
    Document doc = null;
    Exception exception = null;
    try {
      doc = XMLTools.parseDOM(xml);
    }
    catch (ParserConfigurationException exc) { exception = exc; }
    catch (SAXException exc) { exception = exc; }
    catch (IOException exc) { exception = exc; }
    if (exception != null) {
      LOGGER.info("Malformed OME-XML", exception);
      return null;
    }

    Element root = doc.getDocumentElement();
    root.setAttribute("xmlns", SCHEMA_PATH + getLatestVersion());

    // convert tweaked DOM back to XML string
    try {
      xml = XMLTools.getXML(doc);
    }
    catch (TransformerConfigurationException exc) { exception = exc; }
    catch (TransformerException exc) { exception = exc; }
    if (exception != null) {
      LOGGER.info("Internal XML conversion error", exception);
      return null;
    }

    return xml;
  }

  /** @see OMEXMLService#validateOMEXML(java.lang.String) */
  public boolean validateOMEXML(String xml) {
    return validateOMEXML(xml, false);
  }

  /** @see OMEXMLService#validateOMEXML(java.lang.String, boolean) */
  public boolean validateOMEXML(String xml, boolean pixelsHack) {
    // HACK: Inject a TiffData element beneath any childless Pixels elements.
    if (pixelsHack) {
      // convert XML string to DOM
      Document doc = null;
      Exception exception = null;
      try {
        doc = XMLTools.parseDOM(xml);
      }
      catch (ParserConfigurationException exc) { exception = exc; }
      catch (SAXException exc) { exception = exc; }
      catch (IOException exc) { exception = exc; }
      if (exception != null) {
        LOGGER.info("Malformed OME-XML", exception);
        return false;
      }

      // inject TiffData elements as needed
      NodeList list = doc.getElementsByTagName("Pixels");
      for (int i=0; i<list.getLength(); i++) {
        Node node = list.item(i);
        NodeList children = node.getChildNodes();
        boolean needsTiffData = true;
        for (int j=0; j<children.getLength(); j++) {
          Node child = children.item(j);
          String name = child.getLocalName();
          if ("TiffData".equals(name) || "BinData".equals(name)) {
            needsTiffData = false;
            break;
          }
        }
        if (needsTiffData) {
          // inject TiffData element
          Node tiffData = doc.createElement("TiffData");
          node.insertBefore(tiffData, node.getFirstChild());
        }
      }

      // convert tweaked DOM back to XML string
      try {
        xml = XMLTools.getXML(doc);
      }
      catch (TransformerConfigurationException exc) { exception = exc; }
      catch (TransformerException exc) { exception = exc; }
      if (exception != null) {
        LOGGER.info("Internal XML conversion error", exception);
        return false;
      }
    }
    return XMLTools.validateXML(xml, "OME-XML");
  }

  /**
   * @see OMEXMLService#populateOriginalMetadata(loci.formats.ome.OMEXMLMetadata, Hashtable)
   */
  public void populateOriginalMetadata(OMEXMLMetadata omexmlMeta,
    Hashtable<String, Object> metadata)
  {
    ((OMEXMLMetadataImpl) omexmlMeta).resolveReferences();
    OME root = (OME) omexmlMeta.getRoot();
    StructuredAnnotations annotations = root.getStructuredAnnotations();
    if (annotations == null) annotations = new StructuredAnnotations();
    int annotationIndex = annotations.sizeOfXMLAnnotationList();

    for (String key : metadata.keySet()) {
      OriginalMetadataAnnotation annotation = new OriginalMetadataAnnotation();
      annotation.setID(MetadataTools.createLSID("Annotation", annotationIndex));
      annotation.setKey(key);
      annotation.setValue(metadata.get(key).toString());
      annotations.addXMLAnnotation(annotation);
      annotationIndex++;
    }

    root.setStructuredAnnotations(annotations);
    omexmlMeta.setRoot(root);
  }

  /**
   * @see OMEXMLService#populateOriginalMetadata(loci.formats.ome.OMEXMLMetadata, java.lang.String, java.lang.String)
   */
  public void populateOriginalMetadata(OMEXMLMetadata omexmlMeta,
    String key, String value)
  {
    ((OMEXMLMetadataImpl) omexmlMeta).resolveReferences();
    OME root = (OME) omexmlMeta.getRoot();
    StructuredAnnotations annotations = root.getStructuredAnnotations();
    if (annotations == null) annotations = new StructuredAnnotations();
    int annotationIndex = annotations.sizeOfXMLAnnotationList();

    OriginalMetadataAnnotation annotation = new OriginalMetadataAnnotation();
    annotation.setID(MetadataTools.createLSID("Annotation", annotationIndex));
    annotation.setKey(key);
    annotation.setValue(value);
    annotations.addXMLAnnotation(annotation);

    root.setStructuredAnnotations(annotations);
    omexmlMeta.setRoot(root);
  }

  /**
   * @see OMEXMLService#convertMetadata(java.lang.String, loci.formats.meta.MetadataStore)
   */
  public void convertMetadata(String xml, MetadataStore dest)
    throws ServiceException {
    OMEModelObject ome = createRoot(transformToLatestVersion(xml));
    String rootVersion = getOMEXMLVersion(ome);
    String storeVersion = getOMEXMLVersion(dest);
    if (rootVersion.equals(storeVersion)) {
      // metadata store is already an OME-XML metadata object of the
      // correct schema version; populate OME-XML string directly
      if (!(dest instanceof OMEXMLMetadata)) {
        throw new IllegalArgumentException(
            "Expecting OMEXMLMetadata instance.");
      }

      dest.setRoot(ome);
    }
    else {
      // metadata store is incompatible; create an OME-XML
      // metadata object and copy it into the destination
      IMetadata src = createOMEXMLMetadata(xml);
      convertMetadata(src, dest);
    }
  }

  /**
   * @see OMEXMLService#convertMetadata(loci.formats.meta.MetadataRetrieve, loci.formats.meta.MetadataStore)
   */
  public void convertMetadata(MetadataRetrieve src, MetadataStore dest) {
    MetadataConverter.convertMetadata(src, dest);
  }

  /** @see OMEXMLService#removeBinData(OMEXMLMetadata) */
  public void removeBinData(OMEXMLMetadata omexmlMeta) {
    ((OMEXMLMetadataImpl) omexmlMeta).resolveReferences();
    OME root = (OME) omexmlMeta.getRoot();
    List<Image> images = root.copyImageList();
    for (Image img : images) {
      Pixels pix = img.getPixels();
      List<BinData> binData = pix.copyBinDataList();
      for (BinData bin : binData) {
        pix.removeBinData(bin);
      }
    }
    omexmlMeta.setRoot(root);
  }

  /** @see OMEXMLService#removeChannels(OMEXMLMetadata, int, int) */
  public void removeChannels(OMEXMLMetadata omexmlMeta, int image, int sizeC) {
    ((OMEXMLMetadataImpl) omexmlMeta).resolveReferences();
    OME root = (OME) omexmlMeta.getRoot();
    Pixels img = root.getImage(image).getPixels();
    List<Channel> channels = img.copyChannelList();

    for (int c=0; c<channels.size(); c++) {
      Channel channel = channels.get(c);
      if (channel.getID() == null || c >= sizeC) {
        img.removeChannel(channel);
      }
    }
    omexmlMeta.setRoot(root);
  }

  /** @see OMEXMLService#addMetadataOnly(OMEXMLMetadata, int) */
  public void addMetadataOnly(OMEXMLMetadata omexmlMeta, int image) {
    ((OMEXMLMetadataImpl) omexmlMeta).resolveReferences();
    MetadataOnly meta = new MetadataOnly();
    OME root = (OME) omexmlMeta.getRoot();
    Pixels pix = root.getImage(image).getPixels();
    pix.setMetadataOnly(meta);
    omexmlMeta.setRoot(root);
  }

  /** @see OMEXMLService#isEqual(OMEXMLMetadata, OMEXMLMetadata) */
  public boolean isEqual(OMEXMLMetadata src1, OMEXMLMetadata src2) {
    ((OMEXMLMetadataImpl) src1).resolveReferences();
    ((OMEXMLMetadataImpl) src2).resolveReferences();

    OME omeRoot1 = (OME) src1.getRoot();
    OME omeRoot2 = (OME) src2.getRoot();

    DocumentBuilder builder = null;
    try {
      builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    }
    catch (ParserConfigurationException e) {
      return false;
    }

    Document doc1 = builder.newDocument();
    Document doc2 = builder.newDocument();

    Element root1 = omeRoot1.asXMLElement(doc1);
    Element root2 = omeRoot2.asXMLElement(doc2);

    return equals(root1, root2);
  }

  // -- Utility methods - casting --

  /** @see OMEXMLService#asStore(loci.formats.meta.MetadataRetrieve) */
  public MetadataStore asStore(MetadataRetrieve meta) {
    return meta instanceof MetadataStore ? (MetadataStore) meta : null;
  }

  /** @see OMEXMLService#asRetrieve(loci.formats.meta.MetadataStore) */
  public MetadataRetrieve asRetrieve(MetadataStore meta) {
    return meta instanceof MetadataRetrieve ? (MetadataRetrieve) meta : null;
  }

  // -- Helper methods --

  /** Ensures that an xmlns:ome element exists. */
  private String verifyOMENamespace(String xml) {
    try {
      Document doc = XMLTools.parseDOM(xml);
      Element e = doc.getDocumentElement();
      String omeNamespace = e.getAttribute("xmlns:ome");
      if (omeNamespace == null || omeNamespace.equals("")) {
        e.setAttribute("xmlns:ome", e.getAttribute("xmlns"));
      }
      return XMLTools.getXML(doc);
    }
    catch (ParserConfigurationException pce) { }
    catch (TransformerConfigurationException tce) { }
    catch (TransformerException te) { }
    catch (SAXException se) { }
    catch (IOException ioe) { }
    return null;
  }

  /** Compares two Elements for equality. */
  public boolean equals(Node e1, Node e2) {
    NodeList children1 = e1.getChildNodes();
    NodeList children2 = e2.getChildNodes();

    String localName1 = e1.getLocalName();
    if (localName1 == null) {
      localName1 = "";
    }
    String localName2 = e2.getLocalName();
    if (localName2 == null) {
      localName2 = "";
    }
    if (!localName1.equals(localName2)) {
      return false;
    }

    if (localName1.equals("StructuredAnnotations")) {
      // we don't care about StructuredAnnotations at all
      return true;
    }

    NamedNodeMap attributes1 = e1.getAttributes();
    NamedNodeMap attributes2 = e2.getAttributes();

    if (attributes1 == null || attributes2 == null) {
      if ((attributes1 == null && attributes2 != null) ||
        (attributes1 != null && attributes2 == null))
      {
        return false;
      }
    }
    else if (attributes1.getLength() != attributes2.getLength()) {
      return false;
    }
    else {
      // make sure that all of the attributes are equal, except for IDs

      int nAttributes = attributes1.getLength();

      for (int i=0; i<nAttributes; i++) {
        Node n1 = attributes1.item(i);
        String localName = n1.getNodeName();

        if (localName != null && !localName.equals("ID")) {
          Node n2 = attributes2.getNamedItem(localName);
          if (n2 == null) {
            return false;
          }

          if (!equals(n1, n2)) {
            return false;
          }
        }
        else if ("ID".equals(localName)) {
          if (localName1.endsWith("Settings")) {
            // this is a reference to a different node
            // the references are equal if the two referenced nodes are equal

            Node n2 = attributes2.getNamedItem(localName);

            Node realRoot1 = findRootNode(e1);
            Node realRoot2 = findRootNode(e2);

            String refName = localName1.replaceAll("Settings", "");

            Node ref1 = findChildWithID(realRoot1, refName, n1.getNodeValue());
            Node ref2 = findChildWithID(realRoot2, refName, n2.getNodeValue());

            if (ref1 == null && ref2 == null) {
              return true;
            }
            else if ((ref1 == null && ref2 != null) ||
              (ref1 != null && ref2 == null) || !equals(ref1, ref2))
            {
              return false;
            }
          }
        }
      }
    }

    if (children1.getLength() != children2.getLength()) {
      return false;
    }

    Object node1 = e1.getNodeValue();
    Object node2 = e2.getNodeValue();

    if (node1 == null && node2 != null) {
      return false;
    }
    if (node1 != null && !node1.equals(node2) && !localName1.equals("")) {
      return false;
    }

    for (int i=0; i<children1.getLength(); i++) {
      if (!equals(children1.item(i), children2.item(i))) {
        return false;
      }
    }
    return true;
  }

  /** Return the absolute root node for the specified child node. */
  private Node findRootNode(Node child) {
    if (child.getParentNode() != null) {
      return findRootNode(child.getParentNode());
    }
    return child;
  }

  /** Return the child node with specified value for the "ID" attribute. */
  private Node findChildWithID(Node root, String name, String id) {
    NamedNodeMap attributes = root.getAttributes();
    if (attributes != null) {
      Node idNode = attributes.getNamedItem("ID");

      if (idNode != null && id.equals(idNode.getNodeValue()) &&
        name.equals(root.getNodeName()))
      {
        return root;
      }
    }

    NodeList children = root.getChildNodes();

    for (int i=0; i<children.getLength(); i++) {
      Node result = findChildWithID(children.item(i), name, id);
      if (result != null) {
        return result;
      }
    }

    return null;
  }

  // -- Helper class --

  class OriginalMetadataAnnotation extends XMLAnnotation {
    private static final String ORIGINAL_METADATA_NS =
      "openmicroscopy.org/OriginalMetadata";

    private String key, value;

    // -- OriginalMetadataAnnotation methods --

    public void setKey(String key) {
      this.key = key;
    }

    public void setValue(String value) {
      this.value = value;
    }

    // -- XMLAnnotation methods --

    /* @see ome.xml.model.XMLAnnotation#asXMLElement(Document, Element) */
    protected Element asXMLElement(Document document, Element element) {
      if (element == null) {
        element =
          document.createElementNS(XMLAnnotation.NAMESPACE, "XMLAnnotation");
      }

      Element keyElement =
        document.createElementNS(ORIGINAL_METADATA_NS, "Key");
      Element valueElement =
        document.createElementNS(ORIGINAL_METADATA_NS, "Value");
      keyElement.setTextContent(key);
      valueElement.setTextContent(value);

      Element originalMetadata =
        document.createElementNS(ORIGINAL_METADATA_NS, "OriginalMetadata");
      originalMetadata.appendChild(keyElement);
      originalMetadata.appendChild(valueElement);

      Element annotationValue =
        document.createElementNS(XMLAnnotation.NAMESPACE, "Value");
      annotationValue.appendChild(originalMetadata);

      element.appendChild(annotationValue);
      return super.asXMLElement(document, element);
    }

  }

}
