<%@ page session="false" %>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html xmlns="http://www.w3.org/TR/html4/loose.dtd">
<head>
  <meta content="True" name="HandheldFriendly"/>
  <meta
      name="viewport"
      content="initial-scale=1.0, maximum-scale=1.0, user-scalable=0, viewport-fit=cover"
  />
  <meta name="format-detection" content="telephone=no"/>
  <link rel="canonical" href="/map.html"/>
  <title>${version} - Backcountry Mapping Evolved</title>
  <meta
      name="Description"
      content="Powerful trip planning tools and high quality maps.  USGS and USFS topos, aerial imagery, printing to geospatial PDFs, exporting to KMZ and MBTiles."
  />
  <%@include file="head.jsp" %>
  <script type="text/javascript">
    function doload() {
      org.sarsoft.Loader.queue(function() {
        if (sarsoft.tenant == null && sarsoft.account != null && sarsoft.account.canCreateMap === false) {
          sarsoft.permission = ss.Permission.READ;
        } else {
          sarsoft.permission = ss.Permission.ADMIN;
        }

        org.sarsoft.view.initializePage({
          config: org.sarsoft.view.UserDataConfigWidget,
          url: true,
          title: "Unsaved Map"
        });
      });
    }
  </script>
</head>
<body
    onload="doload()"
    class="yui-skin-sam"
    style="width: 100%; height: 100%; position: fixed"
>
<div id="map_canvas" style="width: 100%; height: 100%">
  <c:if test="${upstream}">
    Free USGS topo maps! Use ${version} for collaborative trip planning,
    detailed elevation profiles and terrain analysis, printing geospatial
    PDFs, and exporting maps to your smartphone as KMZ or MBTiles files.
    <ul>
      <li><a href="/print.html">Print free USGS topo PDFs!</a></li>
      <li>
        <a href="/kmz.html"
        >Export topographic maps to Google Earth and Garmin GPS as KML and
          KMZ files.</a
        >
      </li>
      <li><a href="/find">Find shared maps from other users.</a></li>
      <li><a href="/about.html">About ${version}.</a></li>
    </ul>
  </c:if>
</div>
</body>
</html>
