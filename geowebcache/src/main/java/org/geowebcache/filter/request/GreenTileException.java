/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Arne Kepp, The Open Planning Project, Copyright 2008
 *  
 */

package org.geowebcache.filter.request;

import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheDispatcher;

/**
 * Hardcoded to the built-in blank tile for now
 * 
 * @author ak
 */
public class GreenTileException extends RequestFilterException {
    private static Log log = LogFactory.getLog(GreenTileException.class);
    
    private volatile static byte[] greenTile;
    
    public GreenTileException(RequestFilter reqFilter) {
        super(reqFilter, 200, "image/png");
    }

    private byte[] getGreenTile() {
        byte[] green = new byte[659];
        InputStream is = null;
       
        try {
            is = GreenTileException.class.getResourceAsStream("green.png");
            int ret = is.read(green);
            log.info("Read " + ret + " from blank PNG8 file (expected 129).");
        } catch (IOException ioe) {
            log.error(ioe.getMessage());
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        return green;
    }

    
    public byte[] getResponse() {
        byte[] ret = greenTile;
        if (ret == null) {
            synchronized (GreenTileException.class) {
                ret = greenTile;
                if (greenTile == null) {
                    greenTile = ret = getGreenTile();
                }
            }
        }
        return ret;
    }
}
