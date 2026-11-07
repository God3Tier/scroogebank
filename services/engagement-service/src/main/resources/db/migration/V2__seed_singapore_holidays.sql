-- Singapore public holidays for 2026 and 2027, from the Ministry of Manpower
-- (ADR-0054 decision 8). Checked against the MOM page, last updated
-- 2026-06-19, on 2026-10-21. observed marks the Monday in lieu of a holiday
-- that falls on a Sunday. Corrections and later years go in new migrations.

INSERT INTO holidays (id, country_code, holiday_date, name, observed, source, dataset_version) VALUES
    ('5a000000-0000-4000-8000-000000002601', 'SG', DATE '2026-01-01', 'New Year''s Day',   FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002602', 'SG', DATE '2026-02-17', 'Chinese New Year',  FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002603', 'SG', DATE '2026-02-18', 'Chinese New Year',  FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002604', 'SG', DATE '2026-03-21', 'Hari Raya Puasa',   FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002605', 'SG', DATE '2026-04-03', 'Good Friday',       FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002606', 'SG', DATE '2026-05-01', 'Labour Day',        FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002607', 'SG', DATE '2026-05-27', 'Hari Raya Haji',    FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002608', 'SG', DATE '2026-05-31', 'Vesak Day',         FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002609', 'SG', DATE '2026-06-01', 'Vesak Day',         TRUE,  'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002610', 'SG', DATE '2026-08-09', 'National Day',      FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002611', 'SG', DATE '2026-08-10', 'National Day',      TRUE,  'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002612', 'SG', DATE '2026-11-08', 'Deepavali',         FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002613', 'SG', DATE '2026-11-09', 'Deepavali',         TRUE,  'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002614', 'SG', DATE '2026-12-25', 'Christmas Day',     FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002701', 'SG', DATE '2027-01-01', 'New Year''s Day',   FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002702', 'SG', DATE '2027-02-06', 'Chinese New Year',  FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002703', 'SG', DATE '2027-02-07', 'Chinese New Year',  FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002704', 'SG', DATE '2027-02-08', 'Chinese New Year',  TRUE,  'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002705', 'SG', DATE '2027-03-10', 'Hari Raya Puasa',   FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002706', 'SG', DATE '2027-03-26', 'Good Friday',       FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002707', 'SG', DATE '2027-05-01', 'Labour Day',        FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002708', 'SG', DATE '2027-05-17', 'Hari Raya Haji',    FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002709', 'SG', DATE '2027-05-20', 'Vesak Day',         FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002710', 'SG', DATE '2027-08-09', 'National Day',      FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002711', 'SG', DATE '2027-10-28', 'Deepavali',         FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19'),
    ('5a000000-0000-4000-8000-000000002712', 'SG', DATE '2027-12-25', 'Christmas Day',     FALSE, 'https://www.mom.gov.sg/employment-practices/public-holidays', 'MOM-2026-06-19');
