<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html xmlns="http://www.w3.org/TR/html4/loose.dtd">
<head>
  <meta content='True' name='HandheldFriendly'/>
  <meta name="viewport" content="initial-scale=1.0, maximum-scale=1.0, user-scalable=0"/>
  <meta name="format-detection" content="telephone=no"/>
  <%@include file="head.jsp" %>
  <script type="text/javascript">

    org.sarsoft.view.AlertDialog = function(title, bodynode, style) {
      var dlgStyle = { "min-width": "350px", position: "absolute", top: "100px", left: "100px", "z-index": "2500" };
      if (style != null) for (var key in style) {
        dlgStyle[key] = style[key];
      }

      if (typeof (bodynode) == "string") bodynode = jQuery('<div>' + bodynode + '</div>')[0];

      var dlg = jQuery('<div><div class="hd">' + title + '</div></div>');
      dlg.css(dlgStyle);
      this.bd = jQuery('<div class="bd"></div>').appendTo(dlg);
      this.bd.append(bodynode);

      var dialog = null;
      var buttons = [ {
        text: "OK", handler: function() {
          dialog.hide();
        }, isDefault: true
      } ];
      dialog = new YAHOO.widget.Dialog(dlg[0], { buttons: buttons, draggable: true, constraintoviewport: true });
      dialog.render(document.body);
      dialog.hide();
      return dialog;
    }

    clearPOIs = function() {
      $('#poilist').empty();
      if (page.pois != null) {
        page.labelManager.removeAllLabels();
      }
      page.pois = [];
      page.pois_by_range = [];
      page.count = 0;
    }

    setPOIs = function(r, clear) {
      var pois = r.points;
      page.index = r.index;
      if (clear) clearPOIs();
      for (var i = 0; i < pois.length; i++) {
        pois[i].properties.shortname = pois[i].properties.title.replace(/^Mount /, "").replace(/ Mountain$/, "").replace(/ Hill$/, "").replace(/ Peak$/, "");
      }
      page.pois.push.apply(page.pois, pois);
      page.pois_by_range.push.apply(page.pois_by_range, pois);
      page.pois.sort(function(a, b) {
        return b.properties.elevation - a.properties.elevation
      });
      page.pois_by_range.sort(function(a, b) {
        return a.properties.range - b.properties.range
      });

      $('#poilist').empty();
      if (pois.length == 1000) {
        page.count += 1000;
        var d = $('<div style="padding-bottom: 1em; padding-left: 5px"><span style="color: red; font-weight: bold">Tallest ' + page.count + ' Peaks Shown</span></div>').appendTo($('#poilist'));
        $('<div class="underlineOnHover" style="color: blue">Fetch More</div>').appendTo(d).click(function() {
          d.html('Fetching Next 1000 . . .')
          fetchPOIs(page.coordinates, page.alt, page.index);
        });
      }
      pois = page.pois.slice(0).sort(function(a, b) {
        if (a == b) return 0;
        if (a.properties.shortname > b.properties.shortname) return 1;
        return -1;
      });
      for (var i = 0; i < pois.length; i++) {
        var poi = pois[i];
        new function(poi) {
          return $('<span class="underlineOnHover">' + poi.properties.title + '</span>').appendTo($('<div style="padding-left: 5px"></div>').appendTo($('#poilist'))).click(function() {
            m.setCenter(poi.properties.projected.coordinates);
            var range = GeoUtil.distance(page.coordinates, poi.geometry.coordinates);
            m.setZoom(Math.round(Math.log(2 * Math.PI * 256 / Math.asin(10000 / Math.sqrt(100000000 + range * range)))));
          });
        }(poi);
      }
      updateVisiblePOIs();
    }

    updateVisiblePOIs = function() {
      if (page.pois == null) return;
      var bounds = m.getBounds();
      var show = new Array();
      for (var i = 0; i < page.pois_by_range.length; i++) {
        var poi = page.pois_by_range[i];
        if (GeoUtil.boundsContains(bounds, poi.properties.projected.coordinates) && show.length < 25) show.push(poi);
      }
      for (var i = 0; i < page.pois.length; i++) {
        var poi = page.pois[i];
        var contains = GeoUtil.boundsContains(bounds, poi.properties.projected.coordinates);
        if (contains && show.length < 50 && show.indexOf(poi) < 0) show.push(poi);
        else if (poi.label != null && (!contains || show.indexOf(poi) < 0)) {
          page.labelManager.removeLabel(poi.label);
          poi.label = null;
        }
      }

      for (var i = 0; i < show.length; i++) {
        var poi = show[i];
        var px = m.toContainerPixel(poi.properties.projected.coordinates);
        var hide = false;
        for (var j = 0; j < i; j++) {
          if (show[j].label != null && Math.abs(show[j].label.px.x - px.x) < 20 && Math.abs(show[j].label.px.y - px.y) < 20) hide = true;
        }
        if (hide) {
          page.labelManager.removeLabel(poi.label);
          poi.label = null;
        } else {
          if (poi.label == null) {
            var labelOptions = {
              position: poi.properties.projected.coordinates,
              text: poi.properties.shortname,
              style: "white-space: nowrap; background-color: rgba(255, 255, 255, 0.5); -webkit-transform: rotate(315deg); -moz-transform: rotate(315deg); -ms-transform: rotate(315deg)",
              centerOffset: [ 0, -1 ],
              priority: 1
            };
            poi.label = page.labelManager.registerLabel(labelOptions);
          }
          poi.label.px = px;
        }
      }
    }

    function updatePieSlice() {
      var bounds = m.getBounds();
      if (bounds == null || page.coordinates == null) return;

      var coordLeft = GeoUtil.offset(page.coordinates, 200000, bounds[0][GeoUtil.LON]);
      m2.setCoordinates(page.dleft, [ page.coordinates, coordLeft ]);

      var coordRight = GeoUtil.offset(page.coordinates, 200000, bounds[1][GeoUtil.LON]);
      m2.setCoordinates(page.dright, [ page.coordinates, coordRight ]);

      var bottom = Math.round(Math.max(Math.min((86 + bounds[0][GeoUtil.LAT]) * 200 / 172, 200), 0));
      var top = Math.round(Math.max(Math.min((86 + bounds[1][GeoUtil.LAT]) * 200 / 172, 200), 0));
      if (bottom >= top && top > 0) bottom = top - 1;

      $('#altindicator').css({ top: (200 - top) + 'px', bottom: bottom + 'px' });
    }


    fetchPOIs = function(coordinates, alt, index) {
      $.jsrequest('/dem/visible/' + alt + '/' + coordinates[GeoUtil.LAT].toFixed(4) + ',' + coordinates[GeoUtil.LON].toFixed(4) + (index ? '/' + index : ''), {
        success: function(response) {
          if (coordinates != page.coordinates || alt != page.alt) return;
          setPOIs(response, index == null);
        }
      });
    }

    setLayer = function(coordinates) {
      if (coordinates == null) {
        if (page.coordinates == null) return;
        coordinates = page.coordinates
      }
      if (page.coordinates != null && (coordinates[GeoUtil.LAT] != page.coordinates[GeoUtil.LAT] || coordinates[GeoUtil.LON] != page.coordinates[GeoUtil.LON])) {
        m.setZoom(Math.min(4, m.getZoom()));
      }

      var alt = $('#select_eye_alt').val();
      var texture = $('#select_texture').val();
      var quality = 1;
      var l = $('#div_location').find('input');
      var lat = Math.round(coordinates[GeoUtil.LAT] * 10000) / 10000;
      var lng = Math.round(coordinates[GeoUtil.LON] * 10000) / 10000;
      if (l.val() == null || l.val().length == 0) l.val(lat + "," + lng);
      var url = "/dem/viewtile/{Z}/{X}/{Y}.png?location=" + lat + "," + lng + "&alt=" + alt + "&q=" + quality;
      url = url + "&texture=" + (texture || '');
      var type = m.createMapType({
        type: 'TILE',
        alias: 'blank',
        maxresolution: 8,
        minresolution: 3,
        name: "Viewshed",
        template: url
      });
      type.maxZoom = 9;
      // Assuming this view uses Leaflet, limit the viewtile tiles to zoom level 9.
      if (type.options) type.options.maxNativeZoom = 9;
      var id = UUID.create();
      m.setMapType(id, type);
      m.setActiveLayers([ [ id, 1 ] ]);

      if (page.coordinates == null || coordinates[GeoUtil.LAT] != page.coordinates[GeoUtil.LAT] || coordinates[GeoUtil.LON] != page.coordinates[GeoUtil.LON] || alt != page.alt) {
        clearPOIs();
        fetchPOIs(coordinates, alt);

        page.coordinates = coordinates;
        page.alt = alt;

        if (page.dmarker != null) {
          m2.setCoordinates(page.dmarker, coordinates);
          updatePieSlice();
          m2.setCenter(coordinates);
        }
      }
    }

    function doload() {
      org.sarsoft.Loader.queue(function() {

        sarsoft._stupidInSightlineFlag = true;
        page = new Object();
        $(document).ready(function() {
          $(document).bind("contextmenu", function(e) {
            return false;
          })
        });

        if (org.sarsoft.mobile) {
          $(document.body).empty();
          div_top = $('<div id="page_top" class="page_top" style="position: absolute; width: 100%; height: 1.5em; line-height: 1.5em; top: 0; background-color: white; white-space: nowrap">').appendTo(document.body);
          div_container = $('<div id="page_middle" style="position: absolute; left: 0px; bottom: 0px; top: 1.5em; width: 100%">').appendTo(document.body).append($('<div id="map" style="width: 100%; height: 100%"></div>'));
          div_controls = $('<div style="display: inline-block; float: right"></div>').appendTo(div_top);
          div_controls.append($('<select id="select_texture"><option value="">Wireframe</option></select>'));
          div_controls.append($('<span> at </span>'));
          div_controls.append($('<select id="select_eye_alt"></select>'));
          div_controls.append($('<span> up </span>'));
        }

        var layers = sarsoft.view_layers.split(';');
        for (i = 0; i < layers.length; i++) {
          var name = layers[i].split('=')[0];
          var val = layers[i].split('=')[1];
          $('#select_texture').append('<option value="' + val + '">' + name + '</option>');
        }

        var altitudes = sarsoft.view_altitudes.split(';');
        for (i = 0; i < altitudes.length; i++) {
          var name = altitudes[i].split('=')[0];
          var val = altitudes[i].split('=')[1];
          $('#select_eye_alt').append('<option value="' + val + '">' + name + '</option>');
        }

        m = new org.sarsoft.MapWrapper($('#map'), { mapViewer: sarsoft.map_viewer });
        page.labelManager = m.buildSimpleLabelManager();

        m.addZoomControls();
        m.setCenter([ 0, 0 ], 5);
        var type = m.createMapType({
          type: 'TILE',
          alias: 'blank',
          maxresolution: 8,
          minresolution: 3,
          name: "Viewshed",
          template: "/static/images/blank.gif"
        });
        if (m.map.setMaxZoom) m.map.setMaxZoom(9);
        type.maxZoom = 9;
        m.setMapType('blank', type);
        m.setActiveLayers([ [ 'blank', 1 ] ]);

        $('#select_eye_alt').change(function() {
          setLayer()
        });
        $('#select_texture').change(function() {
          setLayer()
        });

        $(m).on('idle', updateVisiblePOIs).on('bounds_changed', updatePieSlice);

        org.sarsoft.async(function() {
          var hash = window.location.hash;
          if (hash.length > 0) {
            if (!org.sarsoft.mobile) window.location.hash = '';
            var props = hash.substring(1).split("&");
            var config = new Object();
            for (var i = 0; i < props.length; i++) {
              var prop = props[i].split("=");
              config[prop[0]] = prop[1];
            }
            $('#select_texture').val(config['t']);
            $('#select_eye_alt').val(config['e']);
            m.setCenter([ Number(config['c'].split(',')[1]), Number(config['c'].split(',')[0]) ], Number(config['z']));
            setLayer([ Number(config['ll'].split(',')[1]), Number(config['ll'].split(',')[0]) ]);
          }
        });

        if (org.sarsoft.mobile) return;

        $(m).on("rightclick", function(evt, coordinates) {
          var point = m.toContainerPixel(coordinates);
          var offset = $(m.getDiv()).offset();
          var info = $('<div></div>');
          var line = $('<div></div>').appendTo(info);
          $('<span class="link">Map This Spot</span>').appendTo(line).click(function() {
            $.jsrequest('/dem/viewid/' + page.coordinates[GeoUtil.LAT].toFixed(4) + ',' + page.coordinates[GeoUtil.LON].toFixed(4) + '/' + page.alt + "/" + coordinates[GeoUtil.LAT].toFixed(4) + "," + coordinates[GeoUtil.LON].toFixed(4), {
              success: function(obj) {
                if (obj.coordinates == null) {
                  alert("Unable to match this spot with a location on the map.");
                  return;
                }
                org.sarsoft.openInNewTab("/map.html#ll=" + obj.coordinates[GeoUtil.LAT] + "," + obj.coordinates[GeoUtil.LON] + "&z=14", "Open location in map");
              }, failure: function(response) {
                throw("AJAX ERROR getting from " + url + ": " + response.responseText);
              }
            });
          });
          m.info(info, coordinates);
        });

        m2 = new org.sarsoft.MapWrapper($('#smallmap'), { mapViewer: sarsoft.map_viewer });
        m2.addZoomControls();
        if (typeof google.maps != "undefined" && typeof google.maps.MapTypeId != "undefined") {
          m2.setActiveLayers([ [ "terrain", 1 ] ]);
        } else {
          m2.setActiveLayers([ [ 'mbt', 1 ] ]);
        }
        m2.setZoom(12);
        var center = m.getCenter();

        page.dmarker = m2.addOverlay({ type: 'Point', coordinates: m.getCenter() }, { 'marker-symbol': 'point', 'marker-color': '#FF0000' });
        m2.setDraggable(page.dmarker, true);
        $(page.dmarker).on('dragend', function() {
          $('#div_location').find('input').val('');
          setLayer(m2.getCoordinates(page.dmarker));
        });
        page.dleft = m2.addOverlay({ type: 'LineString', coordinates: [ center, center ] }, { stroke: '#FF0000', 'stroke-width': 2 });
        page.dright = m2.addOverlay({ type: 'LineString', coordinates: [ center, center ] }, { stroke: '#FF0000', 'stroke-width': 2 });

        $('#locator').click(function() {
          var state = ($('#smallmap').css('display') != 'none');
          if (state) {
            $('#smallmap').css('display', 'none');
            $('#altindicator').parent().css('display', 'none');
            $('#locator').html('show locator').parent().css({ 'height': '16px' });
          } else {
            $('#locator').html('hide locator').parent().css({ 'height': '216px' });
            $('#smallmap').css('display', 'block');
            $('#altindicator').parent().css('display', 'block');
          }
        });

        locationEntryForm = new org.sarsoft.ThinLocationForm();
        locationEntryForm.create($('#div_location'), function() {
          locationEntryForm.read(function(coordinates) {
            setLayer(coordinates);
            m.setZoom(Math.min(4, m.getZoom()));
          });
        });

        var shareBody = $('<div></div>');
        var shareDlg = org.sarsoft.view.AlertDialog("Sharing", shareBody[0]);

        $('#span_share').click(function() {
          var center = m.getCenter();
          var url = window.location.origin + "/view#ll=" + (Math.round(page.coordinates[GeoUtil.LAT] * 10000) / 10000) + "," + (Math.round(page.coordinates[GeoUtil.LON] * 10000) / 10000) + "&e=" + $('#select_eye_alt').val() + "&t=" + $('#select_texture').val() + "&z=" + m.getZoom() + "&c=" + (Math.round(center[GeoUtil.LAT] * 100) / 100) + "," + (Math.round(center[GeoUtil.LON] * 100) / 100);
          const copyspan = $('<span>').text(url).attr('style', 'display: inline-block; white-space: nowrap;');
          org.sarsoft.appendCopyButton(copyspan, { text: url });
          shareBody.text('Share this page using the following URL: ').append(copyspan);
          shareDlg.show();

        });


        $('#span_print').click(function() {
          page.pc.css('display', 'block');
          page.pbc.reset();
        });

        $('#button_goto').click(function() {
          // TODO change elevation etc. to default, update UI also
          locationEntryForm.read(setLayer);
        });

        var pc = page.pc = $('<div style="display: none"></div>').prependTo(('#map_left'));

        var line = $('<div style="margin-top: 1em"><span style="font-weight: bold; font-size: 120%">Print This View</span></div>').appendTo(pc);
        var line = $('<div style="padding-top: 1em"></div>').appendTo(pc);

        if (org.sarsoft.PosterBoxController) {
          var pbc = page.pbc = new org.sarsoft.PosterBoxController(m, line);

          var line = jQuery('<div style="padding-top: 1em"></div>').appendTo(pc);
          var button_print = jQuery('<button>Print</button>').appendTo(line).click(function() {
            org.sarsoft.openInNewTab('/resource/viewpdf?' + pbc.getQueryString() + "&ll=" + page.coordinates[GeoUtil.LAT] + "," + page.coordinates[GeoUtil.LON] + "&e=" + page.alt + "&t=" + $('#select_texture').val(), "Preview PDF");
          });

          var button_cancel = jQuery('<button style="margin-left: 20px">Cancel</button>').appendTo(line).click(function() {
            pc.css('display', 'none');
            if (pbc.box != null) pbc.box.remove();
            pbc.box = null;
          });

        }

        jQuery('<div style="height: 2em"></div>').appendTo(pc);

        $(m).on("idle", function() {
          if (page.coordinates == null) return;
          var center = m.getCenter();
          window.location.hash = "ll=" + (Math.round(page.coordinates[GeoUtil.LAT] * 10000) / 10000) + "," + (Math.round(page.coordinates[GeoUtil.LON] * 10000) / 10000) + "&e=" + $('#select_eye_alt').val() + "&t=" + $('#select_texture').val() + "&z=" + m.getZoom() + "&c=" + (Math.round(center[GeoUtil.LAT] * 100) / 100) + "," + (Math.round(center[GeoUtil.LON] * 100) / 100);
        });

      });


    }

  </script>
</head>
<body onload="doload()" class="yui-skin-sam" style="width: 100%; height: 100%">

<div style="width: 205px; height: 216px; position: absolute; bottom: 0; right: 0; z-index: 3000">
  <div id="locator" style="height: 16px; background-color: #E6E6E6; width: 100%; cursor: pointer; text-align: center; font-size: 10">
    hide locator
  </div>
  <div style="position: relative; width: 5px; height: 200px; background-color: #E6E6E6; float: left; cursor: pointer">
    <div id="altindicator" style="width: 100%; background-color: #FF0000; position: absolute;">
    </div>
  </div>
  <div id="smallmap" style="width: 200px; height: 200px; float: left">
  </div>
</div>

<div style="position: absolute; width: 100%; min-height: 2em; z-index: 2000; top: 0; background-color: #E6E6E6; background-image: -ms-linear-gradient(top, #F0F0F0 0%, #DCDCDC 100%); background-image: -moz-linear-gradient(top, #F0F0F0 0%, #DCDCDC 100%); background-image: -o-linear-gradient(top, #F0F0F0 0%, #DCDCDC 100%); background-image: -webkit-gradient(linear, left top, left bottom, color-stop(0, #F0F0F0), color-stop(1, #DCDCDC)); background-image: -webkit-linear-gradient(top, #F0F0F0 0%, #DCDCDC 100%); background-image: linear-gradient(to bottom, #F0F0F0 0%, #DCDCDC 100%)">

  <div style="float: right; padding-right: 10px">
    <select id="select_texture">
      <option value="">Wireframe</option>
    </select>
    at
    <select id="select_eye_alt"></select>
    up
  </div>

  <div style="float: left"><span style="font-size: 150%; font-weight: bold; color: #666666; padding-left: 5px">${version}</span></div>

  <div style="float: left; padding-left: 10px">
    check out the view from
    <div id="div_location" style="display: inline-block"></div>

    <button id="button_goto">GO</button>

    <span id="span_share" style="margin-left: 10px" class="underlineOnHover">share</span>
    <span id="span_print" style="margin-left: 10px" class="underlineOnHover">print</span>

  </div>

</div>

<div style="position: absolute; top: 2em; left: 0px; bottom: 0px; width: 100%">
  <div id="container" style="height: 100%; padding-left: 200px">

    <div id="map_left" style="position: relative; float: left; height: 100%; display: block; width: 200px; margin-left: -200px; overflow-y: auto" class="cs">
      <div id="poilist"></div>
    </div>

    <div id="map_right" style="height: 100%; position: relative; width: 100%; float: left;">

      <div id="map" style="width: 100%; height: 100%">
      </div>

    </div>
  </div>
</div>

</body>
</html>