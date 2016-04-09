/*
	Radiobeacon - Openbmap wifi and cell logger
    Copyright (C) 2013  wish7

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openbmap.soapclient;

import android.content.Context;
import android.database.Cursor;
import android.text.Html;
import android.util.Log;

import org.openbmap.db.DataHelper;
import org.openbmap.db.DatabaseHelper;
import org.openbmap.db.Schema;
import org.openbmap.db.models.LogFile;
import org.openbmap.utils.XmlSanitizer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;

/**
 * Exports wifis to xml format for later upload.
 */
public class WifiSerializer {

	private static final String TAG = WifiSerializer.class.getSimpleName();

	/**
	 * Cursor windows size, to prevent running out of mem on to large cursor
	 */
	private static final int CURSOR_SIZE	= 3000;

	/**
	 * XML templates
	 */
	private static final String XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>";

	private static final String LOG_XML = "\n<logfile manufacturer=\"%s\" model=\"%s\" revision=\"%s\" swid=\"%s\" swver=\"%s\" exportver=\"%s\">";
	
	private static final String SCAN_XML = "\n<scan time=\"%s\">";

	private static final String POSITION_XML = "\n\t<gps time=\"%s\" lng=\"%s\" lat=\"%s\" alt=\"%s\" hdg=\"%s\" spe=\"%s\" accuracy=\"%s\" type=\"%s\" />";

	private static final String WIFI_XML = "\n\t\t<wifiap bssid=\"%s\" md5essid=\"%s\"%s capa=\"%s\" ss=\"%s\" ntiu=\"%s\"/>";

	/**
	 * XML template closing logfile
	 */
	private static final String	CLOSE_LOGFILE	= "\n</logfile>";


	/**
	 * XML template closing scan tag
	 */
	private static final String	CLOSE_SCAN_TAG	= "\n</scan>";

	/**
	 * Entries per log file
	 */
	private static final int WIFIS_PER_FILE	= 1000;


	private final Context mContext;

	/**
	 * Session Id to export
	 */
	private final int mSession;

	/**
	 * Message in case of an error
	 */
	private final String errorMsg = null;

	/**
	 * Datahelper
	 */
	private final DataHelper mDataHelper;

	/**
	 * Directory where xmls files are stored
	 */
	private final String mTempPath;

	private int	colLastAcc;

	private int	colLastSpeed;

	private int	colLastHead;

	private int	colLastAlt;

	private int	colLastLon;

	private int	colLastTimestamp;

	private int	colLastLat;

	private int	colReqAcc;

	private int	colReqSpeed;

	private int	colReqHead;

	private int	colReqAlt;

	private int	colReqLon;

	private int	colReqTimestamp;

	private int	colReqLat;

	private int	colEndPosId;

	private int	colBeginPosId;

	private int	colTimestamp;

	private int	colLevel;

	private int	colFreq;

	private int	colCapa;

	private int	colMd5Essid;

	private int	colSsid;

	private int	colBssid;

	/**
	 * Timestamp for filename generation, this is generated by looking at the first cell
	 */
	private long mFileTimeStamp;

	/**
	 * Two version numbers are tracked:
	 * 	- Radiobeacon version used for tracking (available from session record)
	 *  - Radiobeacon version used for exporting (available at runtime)
	 * mExportVersion describes the later
	 */
	private final String mExportVersion;

	/**
	 * Anonymise SSIDs?
	 */
	private boolean	mAnonymise = false;

	private static final String WIFI_SQL_QUERY = " SELECT " + Schema.TBL_WIFIS + "." + Schema.COL_ID + " AS \"_id\","
			+ Schema.COL_BSSID + ", "
			+ Schema.COL_SSID + ", "
			+ Schema.COL_MD5_SSID + ", "
			+ Schema.COL_CAPABILITIES + ", "
			+ Schema.COL_FREQUENCY + ", "
			+ Schema.COL_LEVEL + ", "
			+ Schema.TBL_WIFIS + "." + Schema.COL_TIMESTAMP + ", "
			+ Schema.COL_BEGIN_POSITION_ID + " AS \"request_pos_id\","
			+ Schema.COL_END_POSITION_ID + " AS \"last_pos_id\","
			+ Schema.TBL_WIFIS + "." + Schema.COL_SESSION_ID + ", "
			//+ Schema.COL_IS_NEW_WIFI + " AS \"is_new_wifi\","
			+ Schema.COL_KNOWN_WIFI + " AS \"known_wifi\","
			+ " \"req\".\"latitude\" AS \"req_latitude\","
			+ " \"req\".\"longitude\" AS \"req_longitude\","
			+ " \"req\".\"altitude\" AS \"req_altitude\","
			+ " \"req\".\"accuracy\" AS \"req_accuracy\","
			+ " \"req\".\"timestamp\" AS \"req_timestamp\","
			+ " \"req\".\"bearing\" AS \"req_bearing\","
			+ " \"req\".\"speed\" AS \"req_speed\", "
			+ " \"last\".\"latitude\" AS \"last_latitude\","
			+ " \"last\".\"longitude\" AS \"last_longitude\","
			+ " \"last\".\"altitude\" AS \"last_altitude\","
			+ " \"last\".\"accuracy\" AS \"last_accuracy\","
			+ " \"last\".\"timestamp\" AS \"last_timestamp\","
			+ " \"last\".\"bearing\" AS \"last_bearing\","
			+ " \"last\".\"speed\" AS \"last_speed\""
			+ " FROM " + Schema.TBL_WIFIS 
			+ " JOIN \"" + Schema.TBL_POSITIONS + "\" AS \"req\" ON (\"request_pos_id\" = \"req\".\"_id\")"
			+ " JOIN \"" + Schema.TBL_POSITIONS + "\" AS \"last\" ON (\"last_pos_id\" = \"last\".\"_id\")"
			+ " WHERE " + Schema.TBL_WIFIS + "." + Schema.COL_SESSION_ID + " = ?"
			+ " ORDER BY " + Schema.COL_BEGIN_POSITION_ID
			+ " LIMIT " + CURSOR_SIZE
			+ " OFFSET ?";

	/**
	 * Default constructor
	 * @param context	Activities' context
	 * @param session Session id to export
	 * @param tempPath (full) path where temp files are saved. Will be created, if not existing.
	 * @param exportVersion current Radiobeacon version (can differ from Radiobeacon version used for tracking) 
	 */
	public WifiSerializer(final Context context, final int session, String tempPath, final String exportVersion, final boolean anonymise) {
		this.mContext = context;
		this.mSession = session;
		if (tempPath != null && !tempPath.endsWith(File.separator)) {
			tempPath = tempPath + File.separator;
		}
		this.mTempPath = tempPath;
		this.mExportVersion = exportVersion;
		this.mAnonymise  = anonymise;

		ensureTempPath(mTempPath);

		mDataHelper = new DataHelper(context);
	}

	/**
	 * Ensures temp file folder is existing and writeable.
	 * If folder not yet exists, it is created
	 */
	private boolean ensureTempPath(final String path) {
		final File folder = new File(path);

		boolean folderAccessible = false;
		if (folder.exists() && folder.canWrite()) {
			folderAccessible = true;
		}

		if (!folder.exists()) {
			folderAccessible = folder.mkdirs();
		}
		return folderAccessible;
	}

	/**
	 * Builds wifi and cell xml files and saves/uploads them
	 */
	protected final ArrayList<String> export() {
		Log.d(TAG, "Start wifi export. Data source: " + WIFI_SQL_QUERY);

		final LogFile headerRecord = mDataHelper.loadLogFileBySession(mSession);

		final DatabaseHelper mDbHelper = new DatabaseHelper(mContext.getApplicationContext());

		final ArrayList<String> generatedFiles = new ArrayList<String>();

		// get first CHUNK_SIZE records
		Cursor cursorWifis = mDbHelper.getReadableDatabase().rawQuery(WIFI_SQL_QUERY,
				new String[] {String.valueOf(mSession), String.valueOf(0)});

		// [start] init columns
		colBssid = cursorWifis.getColumnIndex(Schema.COL_BSSID);
		colSsid = cursorWifis.getColumnIndex(Schema.COL_SSID);
		colMd5Essid = cursorWifis.getColumnIndex(Schema.COL_MD5_SSID);
		colCapa = cursorWifis.getColumnIndex(Schema.COL_CAPABILITIES);
		colFreq = cursorWifis.getColumnIndex(Schema.COL_FREQUENCY);
		colLevel = cursorWifis.getColumnIndex(Schema.COL_LEVEL);
		colTimestamp = cursorWifis.getColumnIndex(Schema.COL_TIMESTAMP);
		colBeginPosId = cursorWifis.getColumnIndex(Schema.COL_BEGIN_POSITION_ID);
		colEndPosId = cursorWifis.getColumnIndex(Schema.COL_END_POSITION_ID);

		colReqLat = cursorWifis.getColumnIndex("req_" + Schema.COL_LATITUDE);
		colReqTimestamp = cursorWifis.getColumnIndex("req_" + Schema.COL_TIMESTAMP);
		colReqLon = cursorWifis.getColumnIndex("req_" + Schema.COL_LONGITUDE);
		colReqAlt = cursorWifis.getColumnIndex("req_" + Schema.COL_ALTITUDE);
		colReqHead = cursorWifis.getColumnIndex("req_" + Schema.COL_BEARING);
		colReqSpeed = cursorWifis.getColumnIndex("req_" + Schema.COL_SPEED);
		colReqAcc = cursorWifis.getColumnIndex("req_" + Schema.COL_ACCURACY);

		colLastLat = cursorWifis.getColumnIndex("last_" + Schema.COL_LATITUDE);
		colLastTimestamp = cursorWifis.getColumnIndex("last_" + Schema.COL_TIMESTAMP);
		colLastLon = cursorWifis.getColumnIndex("last_" + Schema.COL_LONGITUDE);
		colLastAlt = cursorWifis.getColumnIndex("last_" + Schema.COL_ALTITUDE);
		colLastHead = cursorWifis.getColumnIndex("last_" + Schema.COL_BEARING);
		colLastSpeed = cursorWifis.getColumnIndex("last_" + Schema.COL_SPEED);
		colLastAcc = cursorWifis.getColumnIndex("last_" + Schema.COL_ACCURACY);
		// [end]

		final long startTime = System.currentTimeMillis();

		long outer = 0;
		while (!cursorWifis.isAfterLast()) {
			long i = 0;
			while (!cursorWifis.isAfterLast()) { 
				// creates files of 100 wifis each
				Log.i(TAG, "Cycle " + i);

				final long fileTimeStamp = determineFileTimestamp(cursorWifis);
				final String filename  = mTempPath + generateFilename(fileTimeStamp);

				saveAndMoveCursor(filename, headerRecord, cursorWifis);

				i += WIFIS_PER_FILE;

				generatedFiles.add(filename);
			}
			// fetch next CURSOR_SIZE records
			outer += CURSOR_SIZE;
			cursorWifis.close();
			cursorWifis = mDbHelper.getReadableDatabase().rawQuery(WIFI_SQL_QUERY,
					new String[]{String.valueOf(mSession),
					String.valueOf(outer)});
		}

		final long difference = System.currentTimeMillis() - startTime;
		Log.i(TAG, "Serialize wifi took " + difference + " ms");

		cursorWifis.close();
		cursorWifis = null;
		mDbHelper.close();

		return generatedFiles;
	}


	/**
	 * Gets timestamp from current record
	 * @param cursor
	 * @return
	 */
	private long determineFileTimestamp(final Cursor cursor) {
		cursor.moveToPrevious();
		if (cursor.moveToNext()) {
			final long timestamp = cursor.getLong(colReqTimestamp);
			cursor.moveToPrevious();
			return timestamp;
		}
		return 0;
	}

	/**
	 * Builds a valid wifi log file. The number of records per file is limited (CHUNK_SIZE). Once the limit is reached,
	 * a new file has to be created.
	 * A log file file consists of an header with basic information on cell manufacturer and model, software id and version.
	 * Below the log file header, scans are inserted. Each scan can contain several wifis
	 * @see <a href="http://sourceforge.net/apps/mediawiki/myposition/index.php?title=Wifi_log_format">openBmap format specification</a>
	 * @param headerRecord Header information record
	 * @param cursor Cursor to read from
	 */
	private String saveAndMoveCursor(final String fileName, final LogFile headerRecord, final Cursor cursor) {
		// for performance reasons direct database access is used here (instead of content provider)
		//TODO: behaves strange on non-ascii characters, maybe get ideas from https://android.googlesource.com/platform/frameworks/base.git/+/android-4.2.2_r1/wifi/java/android/net/wifi/WifiSsid.java
		try {
			cursor.moveToPrevious();

			File file = new File(fileName);
			Writer bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file.getAbsoluteFile()), "UTF-8"), 30 * 1024);

			// Write header
			bw.write(XML_HEADER);
			bw.write(logToXml(headerRecord.getManufacturer(), headerRecord.getModel(), headerRecord.getRevision(), headerRecord.getSwid(), headerRecord.getSwVersion(), mExportVersion));

			long previousBeginId = 0;
			String previousEnd = "";

			int i = 0;
			// Iterate wifis cursor until last row reached or WIFIS_PER_FILE is reached 			
			while (i < WIFIS_PER_FILE && cursor.moveToNext()) {

				final long beginId = Long.valueOf(cursor.getString(colBeginPosId));

				final String currentBegin = positionToXml(
						cursor.getLong(colReqTimestamp),
						cursor.getDouble(colReqLon),
						cursor.getDouble(colReqLat),
						cursor.getDouble(colReqAlt),
						cursor.getDouble(colReqHead),
						cursor.getDouble(colReqSpeed),
						cursor.getDouble(colReqAcc),
						"begin");

				final String currentEnd = positionToXml(
						cursor.getLong(colLastTimestamp),
						cursor.getDouble(colLastLon),
						cursor.getDouble(colLastLat),
						cursor.getDouble(colLastAlt) ,
						cursor.getDouble(colLastHead),
						cursor.getDouble(colLastSpeed),
						cursor.getDouble(colLastAcc),
						"end");

				if (i == 0) {
					// Write first scan and gps tag at the beginning
					bw.write(scanToXml(cursor.getLong(colTimestamp)));
					bw.write(currentBegin);
				} else {
					// Later on, scan and gps tags are only needed, if we have a new scan
					if (beginId != previousBeginId) {

						// write end gps tag for previous scan
						bw.write(previousEnd);
						bw.write(CLOSE_SCAN_TAG);

						// Write new scan and gps tag 
						// TODO distance calculation, seems optional
						bw.write(scanToXml(cursor.getLong(colTimestamp)));
						bw.write(currentBegin);
					}
				}

				/*
				 *  At this point, we will always have an open scan and gps tag,
				 *  so write wifi xml now
				 *  Note that for performance reasons all columns are casted to strings
				 *  
				 *  BSSID: in xml files mac is printed without ":" (as opposed to database) for backwards compatibility
				 *  SSID: ssid can contain invalid characters, so sanitize..
				 */
				bw.write(wifiToXml(
						cursor.getString(colBssid).replace(":", ""),
						cursor.getString(colMd5Essid), 
						// sanitizing moved to wifiToXml function
						/*XmlSanitizer.sanitize(cursor.getString(colSsid)),*/
						cursor.getString(colSsid),
						cursor.getString(colCapa),
						cursor.getString(colLevel),
						cursor.getString(colFreq),
						mAnonymise));

				previousBeginId = beginId;
				previousEnd = currentEnd;

				i++;
			}

			// If we are at the last wifi, close open scan and gps tag
			bw.write(previousEnd);
			bw.write(CLOSE_SCAN_TAG);

			bw.write(CLOSE_LOGFILE);
			// ensure that everything is really written out and close 
			bw.close();
			file = null;
			bw = null;
			return fileName;
		} catch (final IOException ioe) {
			cursor.close();
			ioe.printStackTrace();
			return null;
		}
	}

	/**
	 * Generates scan tag
	 * @param timestamp
	 * @return
	 */
	private static String scanToXml(final long timestamp) {
		return String.format(SCAN_XML, timestamp);
	}

	/**
	 * Generates log file header
	 * @param manufacturer
	 * @param model
	 * @param revision
	 * @param swid
	 * @param swVersion
	 * @return log tag
	 */
	private static String logToXml(final String manufacturer, final String model, final String revision, final String swid, final String swVersion, final String exportVersion) {
		return String.format(LOG_XML, manufacturer, model, revision, swid, swVersion, exportVersion);
	}

	/**
	 * Generates position tag
	 * @param reqTime
	 * @param lng
	 * @param lat
	 * @param alt
	 * @param head
	 * @param speed
	 * @param acc
	 * @param type 
	 * @return position tag
	 */
	private static String positionToXml(final long reqTime, final double lng, final double lat,
			final double alt, final double head, final double speed, final double acc, final String type) {
		return String.format(POSITION_XML, reqTime, lng, lat, alt, head, speed, acc, type);
	}

	/**
	 * Generates wifi tag
	 * @param bssid
	 * @param md5essid
	 * @param ssid
	 * @param capa
	 * @param level
	 * @param freq
	 * @return
	 */
	private static String wifiToXml(final String bssid, final String md5essid, final String ssid, final String capa, final String level, final String freq, final boolean anonymise) {

		String ssidXmlOrNothing = "";
		// add only if user has chosen to send ssid and ssid is pure ASCII
		if (!anonymise && XmlSanitizer.isValid(ssid)) {
			ssidXmlOrNothing = String.format(" ssid=\"%s\"", ssid);
		} else if (!anonymise && !XmlSanitizer.isValid(ssid)) {
			Log.i(TAG, "Skipping no ascii ssid " + ssid);
		}

		return String.format(WIFI_XML, bssid, md5essid, ssidXmlOrNothing, capa, level, freq);
	}

	// http://stackoverflow.com/questions/6502759/how-to-strip-or-escape-html-tags-in-android
	public static String stripHtml(final String html) {
	    return Html.fromHtml(html).toString();
	}
	
	/**
	 * Generates filename
	 * Template for wifi logs:
	 * username_V1_250_log20120110201943-wifi.xml
	 * i.e. [username]_V[format version]_log[date]-wifi.xml
	 * Keep in mind, that openbmap server currently only accepts filenames following the above mentioned
	 * naming pattern, otherwise files are ignored.
	 * @param timestamp timestamp of first wifi entry
	 * @return filename
	 */
	private String generateFilename(final long timestamp) {	
		/**
		 * Option 1: generate filename by export time
		 */
		/*
		// Caution filename collisions possible, if called in less than a second
		SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
		formatter.setCalendar(mTimestamp);

		// to avoid file name collisions we add 1 second on each call
		mTimestamp.add(Calendar.SECOND, 1);
		return "V1_" + formatter.format(mTimestamp.getTime()) + "-wifi.xml";
		 */

		/**
		 * 		 * Option 2: generate by first timestamp in file
		 */
		return "V1_log" + timestamp + "-wifi.xml";
	}

}
