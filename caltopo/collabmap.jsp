<%@ page session="false" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>

<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<html prefix="og: http://ogp.me/ns#">
<head>
  <meta content='True' name='HandheldFriendly'/>
  <meta name="viewport" content="initial-scale=1.0, maximum-scale=1.0, user-scalable=0, viewport-fit=cover"/>
  <meta name="format-detection" content="telephone=no"/>
  <meta http-equiv="Content-Type" content="text/html; charset=utf-8">

  <meta property="og:title" content="${fn:escapeXml(tenant.title)} - ${version}"/>
  <meta property="og:url" content="${serverUrl}/m/${tenant.id}"/>
  <meta property="og:image" content="${serverUrl}/pv/${tenant.id}"/>
  <meta property="og:type" content="website"/>
  <c:choose>
    <c:when test="${not empty tenant.description}">
      <meta property="og:description" content="${fn:escapeXml(tenant.description)}"/>
      <meta name="description" content="${fn:escapeXml(tenant.description)}"/>
    </c:when>
    <c:otherwise>
      <meta name="description" content="User created map: ${fn:escapeXml(tenant.title)}, ID ${tenant.id}, on ${version}. Backcountry or your backyard; research, plan, share, and take maps with you on your mobile device. View real time data, choose layers that suit your needs, print maps, record tracks and more."/>
      <meta property="og:description" content="User created map: ${fn:escapeXml(tenant.title)}, ID ${tenant.id}, on ${version}. Backcountry or your backyard; research, plan, share, and take maps with you on your mobile device. View real time data, choose layers that suit your needs, print maps, record tracks and more."/>
    </c:otherwise>
  </c:choose>

  <link rel="canonical" href="${serverUrl}/m/${tenant.id}"/>
  <title>${fn:escapeXml(tenant.title)} - ${version}</title>

  <script type="text/javascript">
    sarsoft = window.sarsoft || {};
    sarsoft.tenant =${js_tenant};
    sarsoft.permission =${js_permission};
  </script>
  <%@include file="head.jsp" %>
  <script type="text/javascript">
    function doload() {
      org.sarsoft.Loader.queue(function() {

        org.sarsoft.view.initializePage({
          config: org.sarsoft.view.PersistedConfigWidget,
          url: false,
          title: sarsoft.tenant.title
        })
      });
    }
  </script>
</head>
<body onload="doload()" class="yui-skin-sam" style="border: 0px; margin: 0px; padding: 0px; width: 100%; height: 100%; position: fixed">

<div id="map_canvas" style="width: 100%; height: 100%">
  <c:if test="${not empty tenant.description}">${fn:escapeXml(tenant.description)}<br/></c:if>
</div>

</body>
</html>