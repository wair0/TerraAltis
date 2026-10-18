<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html xmlns="http://www.w3.org/TR/html4/loose.dtd">
<head>
  <meta content='True' name='HandheldFriendly'/>
  <meta name="viewport" content="initial-scale=1.0, maximum-scale=1.0, user-scalable=0, viewport-fit=cover"/>
  <meta name="format-detection" content="telephone=no"/>
  <%@include file="head.jsp" %>
  <script type="text/javascript">

    coverage =${js_coverage};
    offlineLayers =${js_layers};

    function doload() {
      org.sarsoft.Loader.queue(function() {
          page = new sarsoft.Page({
            ui: org.sarsoft.BaseMapUI,
            title: sarsoft.version + " Coverage",
            growMap: false,
            hideZoomExtent: true,
            saveDraft: false,
            disableRotation: true,
            map: { useDefaultClickHandlers: false, showExtentsButton: false, hide3D: true },
          });
          page.create().then(() => {
              if (sarsoft.app && org.sarsoft.singlePaneUI) {
                // hide sidebars in tablet app
                if (page.rbc) page.rbc.hide();
                $('#page_left').hide();
              }
              new org.sarsoft.MapURLHashWidget(imap, true);

              imap.map.setZoom(Math.min(imap.map.getZoom(), sarsoft.app ? 10 : 12))
              window.renderReact();
            }
          );
        }
      );
    }

  </script>
</head>
<body onload="doload()" class="yui-skin-sam" style="width: 100%; height: 100%; position: fixed">
<div id="map_canvas" style="width: 100%; height: 100%">
</div>
</body>
</html>
