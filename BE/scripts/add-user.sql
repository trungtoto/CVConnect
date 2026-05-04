USE `cvconnect-user-service`;
-- =========================================================
-- 1. TẠO TÀI KHOẢN ỨNG VIÊN (CANDIDATE)
-- Username: candidate1 | Pass: 123456
-- =========================================================
INSERT INTO users (username, password, email, full_name, phone_number, is_email_verified, access_method, created_by)
VALUES ('candidate1', '$2a$10$//aPySVETPhRYx/6xFKev.4S81w7Oq6zs44rnl9aeNe.u7W7GdFaq', 'candidate1@cvconnect.local', 'Nguyễn Văn Ứng Viên', '0901000001', 1, 'LOCAL', 'admin');
SET @candidate_id = LAST_INSERT_ID();
-- Thêm vào bảng candidates
INSERT INTO candidates (user_id, created_by) VALUES (@candidate_id, 'admin');
-- Cấp quyền (role)
INSERT INTO role_user (user_id, role_id, is_default, created_by)
VALUES (@candidate_id, (SELECT id FROM roles WHERE code = 'CANDIDATE'), 1, 'admin');
-- =========================================================
-- 2. TẠO TÀI KHOẢN QUẢN TRỊ TỔ CHỨC (ORG_ADMIN)
-- Username: orgadmin1 | Pass: 123456
-- =========================================================
INSERT INTO users (username, password, email, full_name, phone_number, is_email_verified, access_method, created_by)
VALUES ('orgadmin1', '$2a$10$//aPySVETPhRYx/6xFKev.4S81w7Oq6zs44rnl9aeNe.u7W7GdFaq', 'orgadmin1@cvconnect.local', 'Trần Quản Trị', '0902000001', 1, 'LOCAL', 'admin');
SET @orgadmin_id = LAST_INSERT_ID();
-- Thêm vào bảng org_members (gia nhập công ty số 1)
INSERT INTO org_members (user_id, org_id, created_by) VALUES (@orgadmin_id, 1, 'admin');
-- Cấp quyền (role)
INSERT INTO role_user (user_id, role_id, is_default, created_by)
VALUES (@orgadmin_id, (SELECT id FROM roles WHERE code = 'ORG_ADMIN'), 1, 'admin');
-- =========================================================
-- 3. TẠO TÀI KHOẢN NHÂN VIÊN TUYỂN DỤNG (HR)
-- Username: hr1 | Pass: 123456
-- =========================================================
INSERT INTO users (username, password, email, full_name, phone_number, is_email_verified, access_method, created_by)
VALUES ('hr1', '$2a$10$//aPySVETPhRYx/6xFKev.4S81w7Oq6zs44rnl9aeNe.u7W7GdFaq', 'hr1@cvconnect.local', 'Lê Nhân Sự', '0903000001', 1, 'LOCAL', 'admin');
SET @hr_id = LAST_INSERT_ID();
INSERT INTO org_members (user_id, org_id, created_by) VALUES (@hr_id, 1, 'admin');
INSERT INTO role_user (user_id, role_id, is_default, created_by)
VALUES (@hr_id, (SELECT id FROM roles WHERE code = 'HR'), 1, 'admin');
-- =========================================================
-- 4. TẠO TÀI KHOẢN NGƯỜI PHỎNG VẤN (INTERVIEWER)
    -- Username: interviewer1 | Pass: 123456
-- =========================================================
INSERT INTO users (username, password, email, full_name, phone_number, is_email_verified, access_method, created_by)
VALUES ('interviewer1', '$2a$10$//aPySVETPhRYx/6xFKev.4S81w7Oq6zs44rnl9aeNe.u7W7GdFaq', 'interviewer1@cvconnect.local', 'Phạm Phỏng Vấn', '0904000001', 1, 'LOCAL', 'admin');
SET @interviewer_id = LAST_INSERT_ID();
INSERT INTO org_members (user_id, org_id, created_by) VALUES (@interviewer_id, 1, 'admin');
INSERT INTO role_user (user_id, role_id, is_default, created_by)
VALUES (@interviewer_id, (SELECT id FROM roles WHERE code = 'INTERVIEWER'), 1, 'admin');

USE `cvconnect-user-service`;

-- 1. Cấp quyền module Tin tuyển dụng (ORG_JOB_AD)
SET @menu_jobad = (SELECT id FROM menus WHERE code = 'ORG_JOB_AD' LIMIT 1);
INSERT INTO role_menu (role_id, menu_id, permission, created_by) VALUES
                                                                     (3, @menu_jobad, 'VIEW,ADD,UPDATE,DELETE,EXPORT', 'admin'),
                                                                     (4, @menu_jobad, 'VIEW,ADD,UPDATE,DELETE,EXPORT', 'admin')
ON DUPLICATE KEY UPDATE permission = VALUES(permission);

-- 2. Cấp quyền module Ứng viên (ORG_CANDIDATE)
SET @menu_candidate = (SELECT id FROM menus WHERE code = 'ORG_CANDIDATE' LIMIT 1);
INSERT INTO role_menu (role_id, menu_id, permission, created_by) VALUES
                                                                     (3, @menu_candidate, 'VIEW,ADD,UPDATE,DELETE,EXPORT', 'admin'),
                                                                     (4, @menu_candidate, 'VIEW,ADD,UPDATE,DELETE,EXPORT', 'admin')
ON DUPLICATE KEY UPDATE permission = VALUES(permission);

-- 3. Cấp quyền Lịch phỏng vấn (ORG_CALENDAR)
SET @menu_calendar = (SELECT id FROM menus WHERE code = 'ORG_CALENDAR' LIMIT 1);
INSERT INTO role_menu (role_id, menu_id, permission, created_by) VALUES
                                                                     (3, @menu_calendar, 'VIEW,ADD,UPDATE,DELETE,EXPORT', 'admin'),
                                                                     (4, @menu_calendar, 'VIEW,ADD,UPDATE,DELETE,EXPORT', 'admin')
ON DUPLICATE KEY UPDATE permission = VALUES(permission);

USE `cvconnect-user-service`;

-- 1. Cấp quyền Lịch phỏng vấn (Quan trọng nhất: Xem, Thêm/Xếp lịch, Sửa)
SET @menu_calendar = (SELECT id FROM menus WHERE code = 'ORG_CALENDAR' LIMIT 1);
INSERT INTO role_menu (role_id, menu_id, permission, created_by) VALUES
    (5, @menu_calendar, 'VIEW,ADD,UPDATE,DELETE,EXPORT', 'admin')
ON DUPLICATE KEY UPDATE permission = VALUES(permission);

-- 2. Cấp quyền Xem Tin tuyển dụng & Ứng viên (Để có dữ liệu khi xếp lịch)
SET @menu_jobad = (SELECT id FROM menus WHERE code = 'ORG_JOB_AD' LIMIT 1);
INSERT INTO role_menu (role_id, menu_id, permission, created_by) VALUES
    (5, @menu_jobad, 'VIEW', 'admin')
ON DUPLICATE KEY UPDATE permission = VALUES(permission);

SET @menu_candidate = (SELECT id FROM menus WHERE code = 'ORG_CANDIDATE' LIMIT 1);
INSERT INTO role_menu (role_id, menu_id, permission, created_by) VALUES
    (5, @menu_candidate, 'VIEW', 'admin')
ON DUPLICATE KEY UPDATE permission = VALUES(permission);


-- Của core-service
-- Của core-service
-- Của core-service
-- Thêm một công ty mẫu với ID = 1 để khớp với tài khoản
INSERT INTO organization (id, name, description, website, staff_count_from, staff_count_to, created_by)
VALUES (1, 'Công ty CVConnect', 'Công ty mẫu để test chức năng', 'cvconnect.local', 10, 50, 'admin');
-- Cập nhật lại bộ đếm ID (để sau này thêm công ty mới không bị lỗi trùng ID=1)