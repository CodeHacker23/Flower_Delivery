--
-- PostgreSQL database dump
--

-- Dumped from database version 17.2
-- Dumped by pg_dump version 17.2

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: courier_transactions; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.courier_transactions (
    id uuid NOT NULL,
    amount numeric(10,2) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    description character varying(255),
    type character varying(30) NOT NULL,
    courier_id uuid NOT NULL,
    order_id uuid
);


ALTER TABLE public.courier_transactions OWNER TO postgres;

--
-- Name: couriers; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.couriers (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    full_name character varying(255) NOT NULL,
    is_active boolean NOT NULL,
    phone character varying(50) NOT NULL,
    status character varying(20) NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    user_id uuid NOT NULL,
    passport_photo_file_id character varying(255),
    last_latitude numeric(10,8),
    last_location_at timestamp(6) without time zone,
    last_longitude numeric(11,8),
    balance numeric(10,2) NOT NULL,
    commission_percent numeric(5,2) NOT NULL,
    CONSTRAINT couriers_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'ACTIVE'::character varying, 'BLOCKED'::character varying])::text[])))
);


ALTER TABLE public.couriers OWNER TO postgres;

--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


ALTER TABLE public.flyway_schema_history OWNER TO postgres;

--
-- Name: order_status_geo_snapshots; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.order_status_geo_snapshots (
    id uuid NOT NULL,
    courier_lat numeric(10,8) NOT NULL,
    courier_lon numeric(11,8) NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    order_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    CONSTRAINT order_status_geo_snapshots_status_check CHECK (((status)::text = ANY ((ARRAY['NEW'::character varying, 'ACCEPTED'::character varying, 'IN_SHOP'::character varying, 'PICKED_UP'::character varying, 'ON_WAY'::character varying, 'DELIVERED'::character varying, 'RETURNED'::character varying, 'CANCELLED'::character varying])::text[])))
);


ALTER TABLE public.order_status_geo_snapshots OWNER TO postgres;

--
-- Name: order_stops; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.order_stops (
    id uuid NOT NULL,
    comment text,
    created_at timestamp(6) without time zone NOT NULL,
    delivered_at timestamp(6) without time zone,
    delivery_address character varying(500) NOT NULL,
    delivery_latitude numeric(10,8),
    delivery_longitude numeric(11,8),
    delivery_price numeric(10,2) NOT NULL,
    distance_km numeric(6,2),
    recipient_name character varying(255) NOT NULL,
    recipient_phone character varying(50) NOT NULL,
    stop_number integer NOT NULL,
    stop_status character varying(20) NOT NULL,
    order_id uuid NOT NULL,
    CONSTRAINT order_stops_stop_status_check CHECK (((stop_status)::text = ANY ((ARRAY['PENDING'::character varying, 'DELIVERED'::character varying, 'FAILED'::character varying])::text[])))
);


ALTER TABLE public.order_stops OWNER TO postgres;

--
-- Name: orders; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.orders (
    id uuid NOT NULL,
    accepted_at timestamp(6) without time zone,
    comment text,
    created_at timestamp(6) without time zone NOT NULL,
    delivered_at timestamp(6) without time zone,
    delivery_address character varying(500) NOT NULL,
    delivery_date date NOT NULL,
    delivery_latitude numeric(10,8),
    delivery_longitude numeric(11,8),
    delivery_price numeric(10,2) NOT NULL,
    is_multi_stop boolean NOT NULL,
    picked_up_at timestamp(6) without time zone,
    recipient_name character varying(255) NOT NULL,
    recipient_phone character varying(50) NOT NULL,
    status character varying(20) NOT NULL,
    total_stops integer NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    courier_id uuid,
    shop_id uuid NOT NULL,
    shop_pickup_confirmation_requested_at timestamp(6) without time zone,
    shop_pickup_confirmed boolean,
    shop_pickup_confirmed_at timestamp(6) without time zone,
    courier_cancel_reason text,
    delivery_interval character varying(20),
    shop_pickup_address_override character varying(500),
    shop_pickup_latitude numeric(10,8),
    shop_pickup_longitude numeric(11,8),
    CONSTRAINT orders_delivery_interval_check CHECK (((delivery_interval)::text = ANY ((ARRAY['MORNING'::character varying, 'DAY'::character varying, 'EVENING'::character varying, 'ASAP'::character varying])::text[]))),
    CONSTRAINT orders_status_check CHECK (((status)::text = ANY ((ARRAY['NEW'::character varying, 'ACCEPTED'::character varying, 'IN_SHOP'::character varying, 'PICKED_UP'::character varying, 'ON_WAY'::character varying, 'DELIVERED'::character varying, 'RETURNED'::character varying, 'CANCELLED'::character varying])::text[])))
);


ALTER TABLE public.orders OWNER TO postgres;

--
-- Name: shops; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.shops (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    is_active boolean NOT NULL,
    latitude numeric(10,8),
    longitude numeric(11,8),
    phone character varying(50),
    pickup_address character varying(500) NOT NULL,
    shop_name character varying(255) NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    user_id uuid NOT NULL
);


ALTER TABLE public.shops OWNER TO postgres;

--
-- Name: users; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.users (
    id uuid NOT NULL,
    created_at timestamp(6) without time zone NOT NULL,
    full_name character varying(255) NOT NULL,
    is_active boolean NOT NULL,
    phone character varying(255),
    role character varying(255),
    telegram_id bigint NOT NULL,
    updated_at timestamp(6) without time zone NOT NULL,
    CONSTRAINT users_role_check CHECK (((role)::text = ANY ((ARRAY['COURIER'::character varying, 'SHOP'::character varying, 'ADMIN'::character varying])::text[])))
);


ALTER TABLE public.users OWNER TO postgres;

--
-- Data for Name: courier_transactions; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.courier_transactions (id, amount, created_at, description, type, courier_id, order_id) FROM stdin;
1c52e3c2-98a9-41e0-81a9-1567431cc17d	-140.00	2026-03-16 00:50:42.343268	Комиссия за заказ a1dbefb2-6318-4fab-ade7-1ef2ee689faf	COMMISSION_CHARGE	97d702c2-c63b-4418-a413-13c1ec985f21	a1dbefb2-6318-4fab-ade7-1ef2ee689faf
a8ed0bca-8103-4f00-8e4f-4428307b6eb9	-60.00	2026-03-16 00:50:42.348772	Комиссия за заказ af608478-6c25-4289-a7d6-804df07a6d4c	COMMISSION_CHARGE	97d702c2-c63b-4418-a413-13c1ec985f21	af608478-6c25-4289-a7d6-804df07a6d4c
91dd3816-1390-4805-9554-0f4e1e97c20e	-80.00	2026-03-19 10:44:25.771757	Комиссия за заказ c5a579b6-a012-425f-9e72-4d51f6b8c9e7	COMMISSION_CHARGE	97d702c2-c63b-4418-a413-13c1ec985f21	c5a579b6-a012-425f-9e72-4d51f6b8c9e7
84fc161c-e235-4bfd-a802-9ceabe073de9	-60.00	2026-03-19 10:44:25.777754	Комиссия за заказ 4d1426f9-0ce5-43ed-a9f9-4d039ea29801	COMMISSION_CHARGE	97d702c2-c63b-4418-a413-13c1ec985f21	4d1426f9-0ce5-43ed-a9f9-4d039ea29801
\.


--
-- Data for Name: couriers; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.couriers (id, created_at, full_name, is_active, phone, status, updated_at, user_id, passport_photo_file_id, last_latitude, last_location_at, last_longitude, balance, commission_percent) FROM stdin;
97d702c2-c63b-4418-a413-13c1ec985f21	2026-03-11 16:41:20.45748	Ларик	t	79191217384	ACTIVE	2026-03-19 10:44:25.727247	c16394a7-1321-4eb5-ac3a-94b1ef4e7b9f	AgACAgIAAxkBAAITdmmxVON4U6QTI5YoYsqS3mmWOznXAAJtFmsbt9CIScc_lCCi8YdyAQADAgADdwADOgQ	55.14949600	2026-03-19 10:33:24.923399	61.37513800	49660.00	20.00
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
1	1	create users table	SQL	V1__create_users_table.sql	470045395	postgres	2026-01-14 17:25:33.194637	38	t
2	2	create shops table	SQL	V2__create_shops_table.sql	-335617660	postgres	2026-01-27 21:45:01.71413	103	t
\.


--
-- Data for Name: order_status_geo_snapshots; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.order_status_geo_snapshots (id, courier_lat, courier_lon, created_at, order_id, status) FROM stdin;
3e535065-c875-4f7c-8207-6c5895faf76b	55.14750000	61.37654900	2026-03-19 01:04:44.58321	a1dbefb2-6318-4fab-ade7-1ef2ee689faf	IN_SHOP
5361bb11-83f3-470d-841c-c9728edb0b1e	55.14747800	61.37657000	2026-03-19 01:05:08.556235	a1dbefb2-6318-4fab-ade7-1ef2ee689faf	DELIVERED
\.


--
-- Data for Name: order_stops; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.order_stops (id, comment, created_at, delivered_at, delivery_address, delivery_latitude, delivery_longitude, delivery_price, distance_km, recipient_name, recipient_phone, stop_number, stop_status, order_id) FROM stdin;
06bb587b-0678-41be-a72c-7d2a9c71e76d	все четко в км ✅	2026-02-04 16:32:13.777474	\N	ленина 86	55.15943450	61.36365150	300.00	2.20	Тест 13	899293941	1	PENDING	082e7d51-d6dd-4969-90c7-62e9471463ab
4b27d55c-173a-45cd-b93b-c5f5abbabac7	Что то	2026-02-04 16:32:13.778484	\N	Академическая 18	55.11213400	61.24970500	1300.00	16.90	Академическая 18	8299494	2	PENDING	082e7d51-d6dd-4969-90c7-62e9471463ab
6fc0eab0-40d9-443b-88f4-1a79fffecb18	все норм ✅	2026-02-04 16:30:22.643048	\N	Декабристов 17	55.13251700	61.37619440	300.00	0.30	Тест 11.2	89429949	2	PENDING	6d610b21-eefc-4796-afd0-d669ac5dfca0
e8d74de9-6ae5-44d9-af0d-032eb4c979cf	км отлично ✅	2026-02-04 16:30:22.639601	\N	Декабристов 37	55.13431840	61.37864690	300.00	2.80	Тест 11	89227312	1	PENDING	6d610b21-eefc-4796-afd0-d669ac5dfca0
\.


--
-- Data for Name: orders; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.orders (id, accepted_at, comment, created_at, delivered_at, delivery_address, delivery_date, delivery_latitude, delivery_longitude, delivery_price, is_multi_stop, picked_up_at, recipient_name, recipient_phone, status, total_stops, updated_at, courier_id, shop_id, shop_pickup_confirmation_requested_at, shop_pickup_confirmed, shop_pickup_confirmed_at, courier_cancel_reason, delivery_interval, shop_pickup_address_override, shop_pickup_latitude, shop_pickup_longitude) FROM stdin;
0ded318d-3b33-4f35-91e8-d66ece4d8b09	2026-03-02 23:52:19.542835	+ 04 м от нормы нормы❓	2026-02-04 16:28:42.503972	\N	Цвиллинга 45	2026-02-04	55.16200000	61.41200000	850.00	f	\N	тест 10	98930029223	CANCELLED	1	2026-03-02 23:52:59.315743	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-01 23:17:10.39801	\N	\N	(	\N	Цвиллинга 36	55.16111300	61.40365600
8ebded84-aba9-4f4f-8d51-cbecd3a262d8	\N	км норм✅	2026-02-04 16:22:14.008986	\N	чайковского 183	2026-02-04	55.17777740	61.35467620	500.00	f	\N	Тест 4	8939939493	NEW	1	2026-03-02 12:27:20.681445	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-01 23:43:38.064946	\N	\N	По	\N	Свободы 97	55.16192900	61.41197900
39af97bb-618e-40de-a660-57881de740cf	\N	км норм рассчитан	2026-02-04 16:20:45.034929	\N	ОСТРОВСКОГО 36 п2 кв15	2026-02-04	55.18817740	61.37577530	500.00	f	\N	Тест 2	89191218343	NEW	1	2026-03-02 12:31:12.669038	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-02 02:41:19.466434	t	2026-03-01 01:02:41.792651	Орала	\N	Энтузиастов 14	55.15584300	61.37535500
6d610b21-eefc-4796-afd0-d669ac5dfca0	\N	км отлично ✅	2026-02-04 16:30:22.638629	\N	Декабристов 37	2026-02-04	55.13431840	61.37864690	600.00	t	\N	Тест 11	89227312	NEW	2	2026-03-02 12:31:22.237126	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-02 02:41:27.805111	t	2026-03-01 01:02:45.250781	Руга	\N	Воровского 28	55.15082300	61.38901700
415799bb-cefa-447e-aa84-dc991672519c	\N	-1 км потерян	2026-02-04 16:21:29.267783	\N	Комсомольский проспект 60	2026-02-04	55.19506750	61.32698580	850.00	f	\N	тест 3	89191232244	NEW	1	2026-03-02 12:31:54.540247	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-01 01:01:47.615782	\N	\N	)27:	\N	Бейвеля 6	55.19307100	61.28077100
49e0d3cc-806c-4345-866e-16ece7dcd2c6	\N	км норм ✅	2026-02-04 16:26:30.198567	\N	ул Ловина 28	2026-02-04	55.16252420	61.44423480	500.00	f	\N	Тест 8	89232423453223	NEW	1	2026-03-02 12:32:02.440462	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-01 00:31:07.637027	\N	\N	Тула	\N	Братьев Кашириных 152	55.16653900	61.29415500
20e9e84b-9394-4345-88aa-5f7ddf348649	\N	км норм ✅	2026-02-04 16:27:29.796195	\N	Труда 72	2026-02-04	55.15500000	61.39000000	1150.00	f	\N	Тест 9	8932424232	NEW	1	2026-03-02 12:39:41.543037	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-02-28 20:53:56.042253	\N	\N	;(	\N	Молодогвардейцев 70	55.17943100	61.32761400
082e7d51-d6dd-4969-90c7-62e9471463ab	2026-03-03 00:34:15.714231	все четко в км ✅	2026-02-04 16:32:13.776496	\N	ленина 86	2026-02-04	55.11114500	61.25149300	1600.00	t	\N	Тест 13	899293941	ACCEPTED	2	2026-03-03 00:34:15.724567	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-02 01:14:19.70376	t	2026-02-28 13:53:36.783637	;	\N	\N	\N	\N
a1dbefb2-6318-4fab-ade7-1ef2ee689faf	2026-03-16 00:50:42.337271	км норм 8км	2026-02-09 13:13:42.322549	2026-03-19 01:05:08.561776	Калинина 20	2026-02-09	55.15800000	61.40500000	700.00	f	2026-03-19 01:04:46.863378	АННА	89227242370	DELIVERED	1	2026-03-19 01:05:08.566763	c16394a7-1321-4eb5-ac3a-94b1ef4e7b9f	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-19 01:04:46.872449	t	2026-02-28 13:45:32.369183	4	\N	Кирова 84	55.16705900	61.40003800
04e9e77d-e88f-45bd-82f7-13ef7cb09f16	\N	Хз	2026-03-01 01:03:37.824388	\N	Энтузиаст 47 п2 кв15	2026-03-01	55.15988800	61.40252700	300.00	f	\N	АРИНА	89191216357	NEW	1	2026-03-02 12:28:06.773324	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-02 02:37:19.376588	\N	\N	Ар	\N	\N	\N	\N
c5a579b6-a012-425f-9e72-4d51f6b8c9e7	2026-03-19 10:44:25.762032	км норм ✅	2026-02-04 16:24:04.524794	\N	ул Разина 6б	2026-02-04	55.14075810	61.41017490	400.00	f	\N	тест 5	8942434533	ACCEPTED	1	2026-03-19 10:44:25.779267	c16394a7-1321-4eb5-ac3a-94b1ef4e7b9f	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-02 03:01:00.468409	t	2026-03-01 01:54:19.264666	5	\N	Цвиллинга 25	55.16480700	61.40559400
1dfb1888-2e13-4e70-b756-894c46f6e856	\N	км норм✅	2026-02-04 16:25:41.956	\N	Бажова 55	2026-02-04	55.18603890	61.46070850	850.00	f	\N	тест 7	903414455224	NEW	1	2026-03-02 12:28:15.239976	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-01 18:21:45.153762	t	2026-02-16 13:38:09.106518	Ап	\N	Худякова 4	55.14792600	61.37880800
dffb0065-cfcb-4370-afb1-4dff07fd2732	\N	нет такого дома в члб и в обл	2026-02-04 16:19:58.604076	\N	Шишкова 15 п2 кв14	2026-02-04	\N	\N	1000.00	f	\N	Тест 1	89191231342	NEW	1	2026-03-02 12:11:46.604824	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-02 02:59:43.517666	t	2026-03-01 17:59:49.127607	;	\N	Тимирязева 46	55.15800000	61.40900000
b0a103d7-3640-418c-bb92-0e4fe1479d21	\N	+1км ??	2026-02-04 16:24:54.773339	\N	Комсомольский пр-кт 41	2026-02-04	55.19409100	61.33898500	1300.00	f	\N	тест 6	89121244245	NEW	1	2026-03-02 12:30:02.04728	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-01 00:23:24.114634	\N	\N	По	\N	Худякова 12	55.14810500	61.37165600
af608478-6c25-4289-a7d6-804df07a6d4c	2026-03-16 00:50:42.342279	Хз	2026-03-01 18:28:32.043921	\N	Ленина 73 п3 кв15	2026-03-01	55.15922420	61.38075210	300.00	f	\N	Арчи	8922746326	CANCELLED	1	2026-03-16 12:17:29.406346	c16394a7-1321-4eb5-ac3a-94b1ef4e7b9f	9e988c3e-1016-42bb-ba9b-127c88dccf28	\N	\N	\N	Хз	MORNING	Свердловский пр. 59	55.16470100	61.39095400
4d1426f9-0ce5-43ed-a9f9-4d039ea29801	2026-03-19 10:44:25.769073	Хз это проверка	2026-03-02 23:53:53.400394	\N	Худякова 14	2026-03-03	55.14709930	61.35620940	300.00	f	\N	Регина	89345753267	CANCELLED	1	2026-03-19 10:47:43.649255	c16394a7-1321-4eb5-ac3a-94b1ef4e7b9f	9e988c3e-1016-42bb-ba9b-127c88dccf28	\N	\N	\N	1	MORNING	\N	\N	\N
60f9710b-dcd8-4106-904e-726dca231235	2026-03-02 23:52:19.546838	Хз	2026-03-01 18:03:06.02722	\N	Калинина 20 п2 кв88	2026-03-01	55.18001470	61.39480690	500.00	f	\N	АНАСТАСИЯ	892274365	CANCELLED	1	2026-03-02 23:52:45.653399	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-02 02:18:17.30324	\N	\N	;	ASAP	Красная 38	55.15902400	61.39280200
68df460e-9c6f-47e9-ab0b-13a6ba2c9d94	2026-03-03 00:34:15.717242	Все четко в км ✅	2026-02-04 16:31:18.633458	\N	Проспект Ленина 83	2026-02-04	55.16700000	61.40000000	500.00	f	\N	Тест 12	899999999999	ACCEPTED	1	2026-03-03 00:34:15.72658	\N	9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-03-02 02:37:31.488568	\N	\N	4	\N	Проспект Ленина 83	55.15829700	61.37138200
\.


--
-- Data for Name: shops; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.shops (id, created_at, is_active, latitude, longitude, phone, pickup_address, shop_name, updated_at, user_id) FROM stdin;
9e988c3e-1016-42bb-ba9b-127c88dccf28	2026-02-04 16:18:31.70754	t	55.14737340	61.37513160	+73517794581	Худякова 13	Лепесток	2026-02-04 16:20:29.228116	03d1e15b-da54-4d5a-954f-b4b9248a7aaa
\.


--
-- Data for Name: users; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.users (id, created_at, full_name, is_active, phone, role, telegram_id, updated_at) FROM stdin;
03d1e15b-da54-4d5a-954f-b4b9248a7aaa	2026-02-04 16:18:16.433841	Иларион Авторазбор	f	\N	SHOP	5679274784	2026-02-04 16:18:19.491813
c16394a7-1321-4eb5-ac3a-94b1ef4e7b9f	2026-02-27 00:56:35.199103	👾👾	f	\N	COURIER	642867793	2026-03-02 16:14:01.177438
\.


--
-- Name: courier_transactions courier_transactions_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.courier_transactions
    ADD CONSTRAINT courier_transactions_pkey PRIMARY KEY (id);


--
-- Name: couriers couriers_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.couriers
    ADD CONSTRAINT couriers_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: order_status_geo_snapshots order_status_geo_snapshots_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.order_status_geo_snapshots
    ADD CONSTRAINT order_status_geo_snapshots_pkey PRIMARY KEY (id);


--
-- Name: order_stops order_stops_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.order_stops
    ADD CONSTRAINT order_stops_pkey PRIMARY KEY (id);


--
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- Name: shops shops_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.shops
    ADD CONSTRAINT shops_pkey PRIMARY KEY (id);


--
-- Name: users uk_dus03vmwyluiy7k2p2gcrqbms; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT uk_dus03vmwyluiy7k2p2gcrqbms UNIQUE (telegram_id);


--
-- Name: shops uk_frcvw4bjeifsxtwi7udccb03u; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.shops
    ADD CONSTRAINT uk_frcvw4bjeifsxtwi7udccb03u UNIQUE (user_id);


--
-- Name: couriers uk_ta9mkedhfmaapexm0rnieih5i; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.couriers
    ADD CONSTRAINT uk_ta9mkedhfmaapexm0rnieih5i UNIQUE (user_id);


--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: postgres
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: orders fk21gttsw5evi5bbsvleui69d7r; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT fk21gttsw5evi5bbsvleui69d7r FOREIGN KEY (shop_id) REFERENCES public.shops(id);


--
-- Name: shops fk34po7mmli7wotimo70r6640ap; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.shops
    ADD CONSTRAINT fk34po7mmli7wotimo70r6640ap FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: courier_transactions fka688nyrx64pdmk5sntk6dfdhb; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.courier_transactions
    ADD CONSTRAINT fka688nyrx64pdmk5sntk6dfdhb FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- Name: order_stops fkcauhskm0p0yd17uk6c36xjfym; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.order_stops
    ADD CONSTRAINT fkcauhskm0p0yd17uk6c36xjfym FOREIGN KEY (order_id) REFERENCES public.orders(id);


--
-- Name: courier_transactions fkgkhb9qn85opi586o2mew5nce7; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.courier_transactions
    ADD CONSTRAINT fkgkhb9qn85opi586o2mew5nce7 FOREIGN KEY (courier_id) REFERENCES public.couriers(id);


--
-- Name: couriers fkikc54fmhn8gjhr4t5hc176hrv; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.couriers
    ADD CONSTRAINT fkikc54fmhn8gjhr4t5hc176hrv FOREIGN KEY (user_id) REFERENCES public.users(id);


--
-- Name: orders fkkda753b42924l6hhnyxt75n6c; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT fkkda753b42924l6hhnyxt75n6c FOREIGN KEY (courier_id) REFERENCES public.users(id);


--
-- PostgreSQL database dump complete
--

