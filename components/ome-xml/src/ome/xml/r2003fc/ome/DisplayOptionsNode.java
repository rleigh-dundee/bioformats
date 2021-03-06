/*
 * ome.xml.r2003fc.ome.DisplayOptionsNode
 *
 *-----------------------------------------------------------------------------
 *
 *  Copyright (C) 2007 Open Microscopy Environment
 *      Massachusetts Institute of Technology,
 *      National Institutes of Health,
 *      University of Dundee,
 *      University of Wisconsin-Madison
 *
 *
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation; either
 *    version 2.1 of the License, or (at your option) any later version.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public
 *    License along with this library; if not, write to the Free Software
 *    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *-----------------------------------------------------------------------------
 */

/*-----------------------------------------------------------------------------
 *
 * THIS IS AUTOMATICALLY GENERATED CODE.  DO NOT MODIFY.
 * Created by curtis via xsd-fu on 2008-10-16 06:18:35-0500
 *
 *-----------------------------------------------------------------------------
 */

package ome.xml.r2003fc.ome;

import ome.xml.DOMUtil;
import ome.xml.OMEXMLNode;
import ome.xml.r2003fc.ome.*;

import org.w3c.dom.Element;

public class DisplayOptionsNode extends OMEXMLNode
{

	// -- Constructors --

	/** Constructs a DisplayOptions node with an associated DOM element. */
	public DisplayOptionsNode(Element element)
	{
		super(element);
	}

	/**
	 * Constructs a DisplayOptions node with an associated DOM element beneath
	 * a given parent.
	 */
	public DisplayOptionsNode(OMEXMLNode parent)
	{
		this(parent, true);
	}

	/**
	 * Constructs a DisplayOptions node with an associated DOM element beneath
	 * a given parent.
	 */
	public DisplayOptionsNode(OMEXMLNode parent, boolean attach)
	{
		super(DOMUtil.createChild(parent.getDOMElement(),
		                          "DisplayOptions", attach));
	}

	// -- DisplayOptions API methods --

	// Element which occurs more than once
	public int getROICount()
	{
		return getChildCount("ROI");
	}

	public java.util.Vector getROIList()
	{
		return getChildNodes("ROI");
	}

	public ROINode getROI(int index)
	{
		return (ROINode) getChildNode("ROI", index);
	}

	// Element which is complex (has sub-elements)
	public ChannelSpecTypeNode getGreenChannel()
	{
		return (ChannelSpecTypeNode)
			getChildNode("ChannelSpecType", "GreenChannel");
	}

	// Element which is complex (has sub-elements)
	public ProjectionNode getProjection()
	{
		return (ProjectionNode)
			getChildNode("Projection", "Projection");
	}

	// Attribute
	public Double getZoom()
	{
		return getDoubleAttribute("Zoom");
	}

	public void setZoom(Double zoom)
	{
		setAttribute("Zoom", zoom);
	}

	// Element which is complex (has sub-elements)
	public ChannelSpecTypeNode getBlueChannel()
	{
		return (ChannelSpecTypeNode)
			getChildNode("ChannelSpecType", "BlueChannel");
	}

	// Attribute
	public String getDisplay()
	{
		return getStringAttribute("Display");
	}

	public void setDisplay(String display)
	{
		setAttribute("Display", display);
	}

	// Element which is complex (has sub-elements)
	public TimeNode getTime()
	{
		return (TimeNode)
			getChildNode("Time", "Time");
	}

	// Element which is complex (has sub-elements)
	public ChannelSpecTypeNode getRedChannel()
	{
		return (ChannelSpecTypeNode)
			getChildNode("ChannelSpecType", "RedChannel");
	}

	// Element which is complex (has sub-elements)
	public GreyChannelNode getGreyChannel()
	{
		return (GreyChannelNode)
			getChildNode("GreyChannel", "GreyChannel");
	}

	// *** WARNING *** Unhandled or skipped property ID

	// -- OMEXMLNode API methods --

	public boolean hasID()
	{
		return true;
	}

}
