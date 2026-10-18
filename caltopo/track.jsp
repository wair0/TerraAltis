<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>

<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<html prefix="og: http://ogp.me/ns#">
<head>
  <meta content='True' name='HandheldFriendly'/>
  <%@include file="head.jsp" %>
  <script type="text/javascript">
    function doload() {
      org.sarsoft.Loader.queue(function() {
        track =${jstrack};
        document.title = track.properties.title;

        map = this.map = new org.sarsoft.MapWrapper($('#mapdiv').empty(), { mapViewer: sarsoft.map_viewer });
        map.setBounds(GeoUtil.envelope(track.geometry));
        map.setActiveLayers([ [ 'mbt', 1 ] ]);

        overlay = map.addOverlay(track.geometry, {
          'stroke': '#FF0000',
          'stroke-width': 4
        });

        var right = $('<div style="float: left; padding-left: 10px"></div>').appendTo(document.body);
        $('<h2></h2>').text(track.properties.title).appendTo(right);

        var start = track.geometry.coordinates[0][3];
        var end = track.geometry.coordinates[track.geometry.coordinates.length - 1][3];
        var totalTimeMs = end - start;
        const totalMinutes = Math.floor(totalTimeMs / 60 / 1000);
        let formattedTime = GeoUtil.formatElapsedTime(totalMinutes, true);

        $('<div></div>').text(new Date(start).toLocaleDateString() + " " + new Date(start).toLocaleTimeString() + " to " + new Date(end).toLocaleTimeString()).appendTo(right);
        $('<div></div>').text("Total Time: " + formattedTime).appendTo(right);
        $('<div></div>').text("Distance: " + GeoUtil.formatDistance(GeoUtil.length(track.geometry))).appendTo(right);
        var gain = $('<div></div>').appendTo(right);
        var loss = $('<div></div>').appendTo(right);

        var form = null;
        var doexport = function(format) {
          if (form == null) form = $('<form style="display: none" action="/rest/out" method="POST"><input type="hidden" name="filename" value="export"/><input type="hidden" name="format"/><input type="hidden" name="state"/></form>').appendTo(document.body);
          var feature = {
            geometry: track.geometry,
            properties: {
              "title": track.properties.title
            }
          }
          form.find('[name="state"]').val(YAHOO.lang.JSON.stringify({ type: "FeatureCollection", features: [ feature ] }));
          form.find('[name="format"]').val(format);
          form.submit();
        }

        var exp = $('<div style="padding-top: 20px">Download As: </div>').appendTo(right);
        $('<div style="display: inline-block; cursor: pointer"><img style="height:1em" src="' + $.img('export.svg') + '"/> GPX</div>').appendTo(exp).click(function() {
          doexport('GPX');
        });
        $('<div style="display: inline-block; cursor: pointer; margin-left: 10px"><img style="height:1em" src="' + $.img('export.svg') + '"/> KML</div>').appendTo(exp).click(function() {
          doexport('KML');
        });
        $('<div style="display: inline-block; cursor: pointer; margin-left: 10px"><img style="height:1em" src="' + $.img('export.svg') + '"/> GeoJSON</div>').appendTo(exp).click(function() {
          doexport('GeoJSON');
        });

        var ps = new org.sarsoft.view.ProfileStats(800, 120);
        var ds = new org.sarsoft.view.DEMStats();

        org.sarsoft.DEMUtils.getStatsForLine(track.geometry.coordinates, function(data) {
          ps.width = $(document.body).width() * 0.9;
          ps.height = 250;
          var analysis = ps.analyzeElevation([ { geometry: track.geometry, pointStats: data } ], "absolute", null, false);
          var div = ps.draw([ { geometry: track.geometry, pointStats: data } ], [ "#FF0000" ], { axes: true, analysis: analysis, metric: false });
          div.css({ 'clear': 'both', 'padding-top': '10px' }).appendTo(document.body);
          gain.text('Elevation Gain: ' + GeoUtil.formatDistance(analysis.elevationChanges[0].gain));
          loss.text('Elevation Loss: ' + GeoUtil.formatDistance(analysis.elevationChanges[0].loss));
        });

      });
    }
  </script>
</head>
<body onload="doload()" class="yui-skin-sam" style="border: 0px; margin: 0px; padding: 0px; width: 100%; height: 100%; position: fixed">

<div id="mapdiv" style="width: 400px; height: 400px; float: left"></div>

</body>
</html>