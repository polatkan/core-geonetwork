//=============================================================================
//===	Copyright (C) 2001-2005 Food and Agriculture Organization of the
//===	United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===	and United Nations Environment Programme (UNEP)
//===
//===	This program is free software; you can redistribute it and/or modify
//===	it under the terms of the GNU General Public License as published by
//===	the Free Software Foundation; either version 2 of the License, or (at
//===	your option) any later version.
//===
//===	This program is distributed in the hope that it will be useful, but
//===	WITHOUT ANY WARRANTY; without even the implied warranty of
//===	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===	General Public License for more details.
//===
//===	You should have received a copy of the GNU General Public License
//===	along with this program; if not, write to the Free Software
//===	Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
//===
//===	Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===	Rome - Italy. email: GeoNetwork@fao.org
//=============================================================================

package org.fao.geonet.services.feedback;

import jeeves.interfaces.Service;
import jeeves.resources.dbms.Dbms;
import jeeves.server.ServiceConfig;
import jeeves.server.UserSession;
import jeeves.server.context.ServiceContext;
import org.fao.geonet.utils.BinaryFile;
import org.fao.geonet.Util;
import org.fao.geonet.utils.Xml;
import org.fao.geonet.GeonetContext;
import org.fao.geonet.constants.Geonet;
import org.fao.geonet.constants.Params;
import org.fao.geonet.domain.ReservedOperation;
import org.fao.geonet.kernel.DataManager;
import org.fao.geonet.kernel.MdInfo;
import org.fao.geonet.lib.Lib;
import org.fao.geonet.services.Utils;
import org.jdom.Element;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

//=============================================================================

/** Adds Limitations/Constraints etc from Metadata record and prepares them for display */

public class AddLimitations implements Service
{
	private static String FS = File.separator;
	private String stylePath;

    // This shouldn't be static because DateFormat is not thread safe
    private final SimpleDateFormat _dateFormat = createDateFormatter();
	//--------------------------------------------------------------------------
	//---
	//--- Init
	//---
	//--------------------------------------------------------------------------

	public void init(String appPath, ServiceConfig params) throws Exception {
		this.stylePath = appPath + FS + Geonet.Path.STYLESHEETS + FS;
	}

	//--------------------------------------------------------------------------
	//---
	//--- Service
	//---
	//--------------------------------------------------------------------------

	public Element exec(Element params, final ServiceContext context) throws Exception
	{
		
		GeonetContext gc = (GeonetContext) context.getHandlerContext(Geonet.CONTEXT_NAME);
    DataManager   dm   = gc.getBean(DataManager.class);
		Dbms dbms = (Dbms) context.getResourceManager().open (Geonet.Res.MAIN_DB);

		String id = Utils.getIdentifierFromParameters(params, context);

		String access   = Util.getParam(params, Params.ACCESS);

		Lib.resource.checkPrivilege(context, id, ReservedOperation.download);

		//--- get metadata info
		MdInfo info = dm.getMetadataInfo(dbms, id);
		if (info == null) 
       throw new IllegalArgumentException("Metadata not found --> " + id);	

		//--- start building response
	 	Element response = new Element("response");
		addElement(response,Params.ID,id);
		addElement(response,Params.UUID,info.uuid);

		//--- now add the files chosen from the interface and record in 'downloaded'
		Element downloaded = new Element("downloaded");
		File dir = new File(Lib.resource.getDir(context, access, id));

		@SuppressWarnings("unchecked")
        List<Element> files = params.getChildren(Params.FNAME);
		for (Element elem : files) {
			response.addContent((Element)elem.clone());

			String fname = elem.getText();

			if (fname.contains("..")) {
				continue;	// Avoid unsecured file name
			}
			
			File file = new File(dir, fname);

			Element fileInfo = new Element("file");

			Element details = BinaryFile.encode(200, file.getAbsolutePath(), false);
			String remoteURL = details.getAttributeValue("remotepath");
			if (remoteURL != null) {
				fileInfo.setAttribute("size","unknown");
				fileInfo.setAttribute("datemodified","unknown");
				fileInfo.setAttribute("name",remoteURL);
			} else {
				fileInfo.setAttribute("size",file.length()+"");
				fileInfo.setAttribute("name",fname);
				Date date = new Date(file.lastModified());
				fileInfo.setAttribute("datemodified",_dateFormat.format(date));
			}
			downloaded.addContent(fileInfo);
		}
		addElement(response,Params.ACCESS,access);

    //--- get metadata
    boolean forEditing = false, withValidationErrors = false, keepXlinkAttributes = false;
    Element elMd = gc.getBean(DataManager.class).getMetadata(context, id, forEditing, withValidationErrors, keepXlinkAttributes);

    if (elMd == null)
       throw new IllegalArgumentException("Metadata not found --> " + id);	

    //--- place xml in metadata element
		Element md = new Element(Geonet.Elem.METADATA);
		md.addContent((Element)elMd.clone());
		response.addContent(md);

		//--- transform record into brief version
		String briefXslt = stylePath + Geonet.File.METADATA_BRIEF;
		Element elBrief = Xml.transform(elMd, briefXslt);

		//--- create root element for passing all the info we've gathered 
		//--- to license annex xslt generator
		Element root = new Element("root");
		elBrief.setAttribute("changedate",info.changeDate);
		elBrief.setAttribute("currdate",now());
		root.addContent(elBrief);
		root.addContent(downloaded);
        if(context.isDebugEnabled())
            context.debug("Passed to metadata-license-annex.xsl:\n "+Xml.getString(root));

		//--- create the license annex html using the info in root element and
		//--- add it to response under license element
		String licenseAnnexXslt = stylePath + Geonet.File.LICENSE_ANNEX_XSL;
		Element license = Xml.transform(root, licenseAnnexXslt);
		response.addContent(new Element("license").addContent(license));

		//--- Now set the id into the users session so that future services can do
		//--- the download
		UserSession session = context.getUserSession();
    Element resourceData  = (Element) session.getProperty(Geonet.Session.FILE_DISCLAIMER);
		if (resourceData == null) {
			resourceData = new Element(Geonet.Session.FILE_DISCLAIMER);
			addElement(resourceData,Params.ID,id);
		} else {
			Element idEl = resourceData.getChild(Params.ID);
			if (idEl == null) addElement(resourceData,Params.ID,id);
			else idEl.setText(id);
		}
		session.setProperty(Geonet.Session.FILE_DISCLAIMER, resourceData);

		//--- now get the users name, organisation and email address to 
		//--- prepopulate the feedback form (if they are logged in)
		if (session.getUserId()	!= null) {
			Element elUser = dbms.select ("SELECT Surname, Name, Email, Organisation FROM Users WHERE id=?", session.getUserIdAsInt());
			Element elRec = elUser.getChild("record");
			if (elRec != null) {
				response.addContent(elRec.cloneContent());
			}
		}

		return response;
	}

	//---------------------------------------------------------------------------

	protected static void addElement(Element root, String name, String value)
	{
	  root.addContent(new Element(name).setText(value));
	}

	//---------------------------------------------------------------------------

	private static String now() {
		Calendar cal = Calendar.getInstance();
		return createDateFormatter().format(cal.getTime());
	}
	
	private static SimpleDateFormat createDateFormatter() {
	    // This shouldn't be static because DateFormat is not thread safe
	    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	}
}

//=============================================================================
