-- 基础信息管理下增加桥梁地图。已有桥梁建筑管理权限的角色一并可见。
INSERT INTO sys_menu (menu_name, parent_id, order_num, url, target, menu_type, visible, is_refresh, perms, icon, create_by, create_time, remark)
SELECT '桥梁地图', 1174, 5, '/biz/building/map', 'menuItem', 'C', '0', '1', 'biz:building:view', 'fa fa-map-marker', 'admin', NOW(), '按卡片GPS标注桥梁'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE url = '/biz/building/map');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role_menu.role_id, menu.menu_id
FROM sys_role_menu role_menu
JOIN sys_menu menu ON menu.url = '/biz/building/map'
WHERE role_menu.menu_id = 1195
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu existing
      WHERE existing.role_id = role_menu.role_id AND existing.menu_id = menu.menu_id
  );
