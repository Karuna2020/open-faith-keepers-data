install-wkhtmltopdf:
	./ops/install-wkhtmltopdf.sh

install-bb:
	./ops/install-bb.sh

install: install-bb install-wkhtmltopdf

start:
	CLOUDINARY_URL=$(cl-url) ./bb main.clj $(token)
