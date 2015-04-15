/*
 * card-muscle.c: Support for MuscleCard Applet from musclecard.com
 *
 * Copyright (C) 2006, Identity Alliance, Thomas Harning <support@identityalliance.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

#include "config.h"

#include <stdlib.h>
#include <string.h>

#include "internal.h"
#include "cardctl.h"
#include "muscle.h"
#include "muscle-filesystem.h"
#include "types.h"
#include "opensc.h"
#include <npa/npa.h>

static struct sc_card_operations muscle_ops;
static const struct sc_card_operations *iso_ops = NULL;

static struct sc_card_driver muscle_drv = {
	"VirtualKeycard",
	"virtualkeycard",
	&muscle_ops,
	NULL, 0, NULL
};

static struct sc_atr_table muscle_atrs[] = {
	/* Virtual Keycard, ATR from Nexus 5 */
	{ "3b:80:80:01:01", NULL, NULL, SC_CARD_TYPE_MUSCLE_JCOP241, 0, NULL },
	{ NULL, NULL, NULL, 0, 0, NULL }
};

#define MUSCLE_DATA(card) ( (muscle_private_t*)card->drv_data )
#define MUSCLE_FS(card) ( ((muscle_private_t*)card->drv_data)->fs )
typedef struct muscle_private {
	sc_security_env_t env;
	unsigned short verifiedPins;
	mscfs_t *fs;
	int rsa_key_ref;
	/* Added for Virtual Keycard */
	unsigned char *ef_cardaccess;
	size_t ef_cardaccess_length;
	int pace_established;
	/* End of added for Virtual Keycard */
	
} muscle_private_t;

static int muscle_finish(sc_card_t *card)
{
	muscle_private_t *priv = MUSCLE_DATA(card);
	mscfs_free(priv->fs);
	free(priv);
	return 0;
}

static u8 muscleAppletId[] = { 0xA0, 0x00,0x00,0x00, 0x01, 0x01 };

static int muscle_match_card(sc_card_t *card)
{
	sc_apdu_t apdu;
	u8 response[64];
	int r;
	
	/* Since we send an APDU, the card's logout function may be called...
	 * however it's not always properly nulled out... */
	card->ops->logout = NULL;

	if (msc_select_applet(card, muscleAppletId, 5) == 1) {
		/* Muscle applet is present, check the protocol version to be sure */		
		sc_format_apdu(card, &apdu, SC_APDU_CASE_2, 0x3C, 0x00, 0x00);
		apdu.cla = 0xB0;
		apdu.le = 64;
		apdu.resplen = 64;
		apdu.resp = response;
		r = sc_transmit_apdu(card, &apdu);
		if (r == SC_SUCCESS && response[0] == 0x01) {
				card->type = SC_CARD_TYPE_MUSCLE_V1;
				return 1;
		}
	}
	return 0;
}

/* Since Musclecard has a different ACL system then PKCS15
 * objects need to have their READ/UPDATE/DELETE permissions mapped for files
 * and directory ACLS need to be set
 * For keys.. they have different ACLS, but are accessed in different locations, so it shouldn't be an issue here
 */
static unsigned short muscle_parse_singleAcl(const sc_acl_entry_t* acl)
{
	unsigned short acl_entry = 0;
	while(acl) {
		int key = acl->key_ref;
		int method = acl->method;
		switch(method) {
		case SC_AC_NEVER:
			return 0xFFFF;
		/* Ignore... other items overwrite these */
		case SC_AC_NONE:
		case SC_AC_UNKNOWN:
			break;
		case SC_AC_CHV:
			acl_entry |= (1 << key); /* Assuming key 0 == SO */
			break;
		case SC_AC_AUT:
		case SC_AC_TERM:
		case SC_AC_PRO:
		default:
			/* Ignored */
			break;
		}
		acl = acl->next;
	}
	return acl_entry;
}

static void muscle_parse_acls(const sc_file_t* file, unsigned short* read_perm, unsigned short* write_perm, unsigned short* delete_perm)
{
	assert(read_perm && write_perm && delete_perm);
	*read_perm =  muscle_parse_singleAcl(sc_file_get_acl_entry(file, SC_AC_OP_READ));
	*write_perm =  muscle_parse_singleAcl(sc_file_get_acl_entry(file, SC_AC_OP_UPDATE));
	*delete_perm =  muscle_parse_singleAcl(sc_file_get_acl_entry(file, SC_AC_OP_DELETE));
}

static int muscle_create_directory(sc_card_t *card, sc_file_t *file)
{
	mscfs_t *fs = MUSCLE_FS(card);
	msc_id objectId;
	u8* oid = objectId.id;
	unsigned id = file->id;
	unsigned short read_perm = 0, write_perm = 0, delete_perm = 0;
	int objectSize;
	int r;
	if(id == 0) /* No null name files */
		return SC_ERROR_INVALID_ARGUMENTS;
	
	/* No nesting directories */
	if(fs->currentPath[0] != 0x3F || fs->currentPath[1] != 0x00)
		return SC_ERROR_NOT_SUPPORTED;
	oid[0] = ((id & 0xFF00) >> 8) & 0xFF;
	oid[1] = id & 0xFF;
	oid[2] = oid[3] = 0;
	
	objectSize = file->size;
	
	muscle_parse_acls(file, &read_perm, &write_perm, &delete_perm);
	r = msc_create_object(card, objectId, objectSize, read_perm, write_perm, delete_perm);
	mscfs_clear_cache(fs);
	if(r >= 0) return 0;
	return r;
}


static int muscle_create_file(sc_card_t *card, sc_file_t *file)
{
	mscfs_t *fs = MUSCLE_FS(card);
	int objectSize = file->size;
	unsigned short read_perm = 0, write_perm = 0, delete_perm = 0;
	msc_id objectId;
	int r;
	if(file->type == SC_FILE_TYPE_DF)
		return muscle_create_directory(card, file);
	if(file->type != SC_FILE_TYPE_WORKING_EF)
		return SC_ERROR_NOT_SUPPORTED;
	if(file->id == 0) /* No null name files */
		return SC_ERROR_INVALID_ARGUMENTS;
	
	muscle_parse_acls(file, &read_perm, &write_perm, &delete_perm);
	
	mscfs_lookup_local(fs, file->id, &objectId);
	r = msc_create_object(card, objectId, objectSize, read_perm, write_perm, delete_perm);
	mscfs_clear_cache(fs);
	if(r >= 0) return 0;
	return r;
}

static int muscle_read_binary(sc_card_t *card, unsigned int idx, u8* buf, size_t count, unsigned long flags)
{
	mscfs_t *fs = MUSCLE_FS(card);
	int r;
	msc_id objectId;
	u8* oid = objectId.id;
	mscfs_file_t *file;
	
	r = mscfs_check_selection(fs, -1);
	if(r < 0) SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, r);
	file = &fs->cache.array[fs->currentFileIndex];
	objectId = file->objectId;
	/* memcpy(objectId.id, file->objectId.id, 4); */
	if(!file->ef) {
		oid[0] = oid[2];
		oid[1] = oid[3];
		oid[2] = oid[3] = 0;
	}
	r = msc_read_object(card, objectId, idx, buf, count);
	SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, r);
}

static int muscle_update_binary(sc_card_t *card, unsigned int idx, const u8* buf, size_t count, unsigned long flags)
{
	mscfs_t *fs = MUSCLE_FS(card);
	int r;
	mscfs_file_t *file;
	msc_id objectId;
	u8* oid = objectId.id;

	r = mscfs_check_selection(fs, -1);
	if(r < 0) SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, r);
	file = &fs->cache.array[fs->currentFileIndex];
	
	objectId = file->objectId;
	/* memcpy(objectId.id, file->objectId.id, 4); */
	if(!file->ef) {
		oid[0] = oid[2];
		oid[1] = oid[3];
		oid[2] = oid[3] = 0;
	}
	if(file->size < idx + count) {
		int newFileSize = idx + count;
		u8* buffer = malloc(newFileSize);
		if(buffer == NULL) SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, SC_ERROR_OUT_OF_MEMORY);
		
		r = msc_read_object(card, objectId, 0, buffer, file->size);
		/* TODO: RETREIVE ACLS */
		if(r < 0) goto update_bin_free_buffer;
		r = msc_delete_object(card, objectId, 0);
		if(r < 0) goto update_bin_free_buffer;
		r = msc_create_object(card, objectId, newFileSize, 0,0,0);
		if(r < 0) goto update_bin_free_buffer;
		memcpy(buffer + idx, buf, count);
		r = msc_update_object(card, objectId, 0, buffer, newFileSize);
		if(r < 0) goto update_bin_free_buffer;
		file->size = newFileSize;
update_bin_free_buffer:
		free(buffer);
		SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, r);
	} else {
		r = msc_update_object(card, objectId, idx, buf, count);
	}
	/* mscfs_clear_cache(fs); */
	return r;
}

/* TODO: Evaluate correctness */
static int muscle_delete_mscfs_file(sc_card_t *card, mscfs_file_t *file_data)
{
	mscfs_t *fs = MUSCLE_FS(card);
	msc_id id = file_data->objectId;
	u8* oid = id.id;
	int r;

	if(!file_data->ef) {
		int x;
		mscfs_file_t *childFile;
		/* Delete children */
		mscfs_check_cache(fs);
		
		sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL,
			"DELETING Children of: %02X%02X%02X%02X\n",
			oid[0],oid[1],oid[2],oid[3]);
		for(x = 0; x < fs->cache.size; x++) {
			msc_id objectId;
			childFile = &fs->cache.array[x];
			objectId = childFile->objectId;
			
			if(0 == memcmp(oid + 2, objectId.id, 2)) {
				sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL,
					"DELETING: %02X%02X%02X%02X\n",
					objectId.id[0],objectId.id[1],
					objectId.id[2],objectId.id[3]);
				r = muscle_delete_mscfs_file(card, childFile);
				if(r < 0) SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_VERBOSE,r);
			}
		}
		oid[0] = oid[2];
		oid[1] = oid[3];
		oid[2] = oid[3] = 0;
		/* ??? objectId = objectId >> 16; */
	}
	if((0 == memcmp(oid, "\x3F\x00\x00\x00", 4))
		|| (0 == memcmp(oid, "\x3F\x00\x3F\x00", 4))) {
	}
	r = msc_delete_object(card, id, 1);
	/* Check if its the root... this file generally is virtual
	 * So don't return an error if it fails */
	if((0 == memcmp(oid, "\x3F\x00\x00\x00", 4))
		|| (0 == memcmp(oid, "\x3F\x00\x3F\x00", 4)))
		return 0;

	if(r < 0) {
		printf("ID: %02X%02X%02X%02X\n",
					oid[0],oid[1],oid[2],oid[3]);
		SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_VERBOSE,r);
	}
	return 0;
}

static int muscle_delete_file(sc_card_t *card, const sc_path_t *path_in)
{
	mscfs_t *fs = MUSCLE_FS(card);
	mscfs_file_t *file_data = NULL;
	int r = 0;

	r = mscfs_loadFileInfo(fs, path_in->value, path_in->len, &file_data, NULL);
	if(r < 0) SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_VERBOSE,r);
	r = muscle_delete_mscfs_file(card, file_data);
	mscfs_clear_cache(fs);
	if(r < 0) SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_VERBOSE,r);
	return 0;
}

static void muscle_load_single_acl(sc_file_t* file, int operation, unsigned short acl)
{
	int key;
	/* Everybody by default.... */
	sc_file_add_acl_entry(file, operation, SC_AC_NONE, 0);
	if(acl == 0xFFFF) {
		sc_file_add_acl_entry(file, operation, SC_AC_NEVER, 0);
		return;
	}
	for(key = 0; key < 16; key++) {
		if(acl >> key & 1) {
			sc_file_add_acl_entry(file, operation, SC_AC_CHV, key);
		}
	}
}
static void muscle_load_file_acls(sc_file_t* file, mscfs_file_t *file_data)
{
	muscle_load_single_acl(file, SC_AC_OP_READ, file_data->read);
	muscle_load_single_acl(file, SC_AC_OP_WRITE, file_data->write);
	muscle_load_single_acl(file, SC_AC_OP_UPDATE, file_data->write);
	muscle_load_single_acl(file, SC_AC_OP_DELETE, file_data->delete);
}
static void muscle_load_dir_acls(sc_file_t* file, mscfs_file_t *file_data)
{
	muscle_load_single_acl(file, SC_AC_OP_SELECT, 0);
	muscle_load_single_acl(file, SC_AC_OP_LIST_FILES, 0);
	muscle_load_single_acl(file, SC_AC_OP_LOCK, 0xFFFF);
	muscle_load_single_acl(file, SC_AC_OP_DELETE, file_data->delete);
	muscle_load_single_acl(file, SC_AC_OP_CREATE, file_data->write);
}

/* Required type = -1 for don't care, 1 for EF, 0 for DF */
static int select_item(sc_card_t *card, const sc_path_t *path_in, sc_file_t ** file_out, int requiredType)
{
	mscfs_t *fs = MUSCLE_FS(card);
	mscfs_file_t *file_data = NULL;
	int pathlen = path_in->len;
	int r = 0;
	int objectIndex;
	u8* oid;
	
	mscfs_check_cache(fs);
	r = mscfs_loadFileInfo(fs, path_in->value, path_in->len, &file_data, &objectIndex);
	if(r < 0) SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_VERBOSE,r);
	
	/* Check if its the right type */
	if(requiredType >= 0 && requiredType != file_data->ef) {
		SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, SC_ERROR_INVALID_ARGUMENTS);
	}
	oid = file_data->objectId.id;
	/* Is it a file or directory */
	if(file_data->ef) {
		fs->currentPath[0] = oid[0];
		fs->currentPath[1] = oid[1];
		fs->currentFile[0] = oid[2];
		fs->currentFile[1] = oid[3];
	} else {
		fs->currentPath[0] = oid[pathlen - 2];
		fs->currentPath[1] = oid[pathlen - 1];
		fs->currentFile[0] = 0;
		fs->currentFile[1] = 0;
	}
	
	fs->currentFileIndex = objectIndex;
	if(file_out) {
		sc_file_t *file;
		file = sc_file_new();
		file->path = *path_in;
		file->size = file_data->size;
		file->id = (oid[2] << 8) | oid[3];
		if(!file_data->ef) {
			file->type = SC_FILE_TYPE_DF;
		} else {
			file->type = SC_FILE_TYPE_WORKING_EF;
			file->ef_structure = SC_FILE_EF_TRANSPARENT;
		}
		
		/* Setup ACLS */
		if(file_data->ef) {
			muscle_load_file_acls(file, file_data);
		} else {
			muscle_load_dir_acls(file, file_data);
			/* Setup directory acls... */
		}
		
		file->magic = SC_FILE_MAGIC;
		*file_out = file;
	}
	return 0;
}

static int muscle_select_file(sc_card_t *card, const sc_path_t *path_in,
			     sc_file_t **file_out)
{
	int r;
	
	assert(card != NULL && path_in != NULL);
	
	switch (path_in->type) {
	case SC_PATH_TYPE_FILE_ID:
		r = select_item(card, path_in, file_out, 1);
		break;
	case SC_PATH_TYPE_DF_NAME:
		r = select_item(card, path_in, file_out, 0);
		break;
	case SC_PATH_TYPE_PATH:
		r = select_item(card, path_in, file_out, -1);
		break;
	default:
		SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_VERBOSE, SC_ERROR_INVALID_ARGUMENTS);
	}
	if(r > 0) r = 0;
	SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_VERBOSE,r);
}

static int _listFile(mscfs_file_t *file, int reset, void *udata)
{
	int next = reset ? 0x00 : 0x01;
	return msc_list_objects( (sc_card_t*)udata, next, file);
}

static int muscle_init(sc_card_t *card)
{	
	muscle_private_t *priv;
	
	//Added for Virtual Keycard
	if(!muscle_match_card(card)){
		return SC_ERROR_NO_CARD_SUPPORT;
	}
	//End of added for Virtual Keycard
	
	card->name = "VirtualKeycard";
	card->drv_data = malloc(sizeof(muscle_private_t));
	if(!card->drv_data) {
		SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, SC_ERROR_OUT_OF_MEMORY);
	}
	memset(card->drv_data, 0, sizeof(muscle_private_t));
	priv = MUSCLE_DATA(card);
	priv->verifiedPins = 0;
	priv->fs = mscfs_new();
	if(!priv->fs) {
		free(card->drv_data);
		SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, SC_ERROR_OUT_OF_MEMORY);
	}
	priv->fs->udata = card;
	priv->fs->listFile = _listFile;

	card->cla = 0xB0;
	
	card->flags |= SC_CARD_FLAG_RNG;
	card->caps |= SC_CARD_CAP_RNG;

	/* Card type detection */
	_sc_match_atr(card, muscle_atrs, &card->type);
	//Commented out for virtual keycard: We are not able to handle extended apdus.
	//Uncommented: Needed, because error will occur in muscle_partial_read_object
	if(card->type == SC_CARD_TYPE_MUSCLE_ETOKEN_72K) {
		card->caps |= SC_CARD_CAP_APDU_EXT;
	}
	if(card->type == SC_CARD_TYPE_MUSCLE_JCOP241) {
		card->caps |= SC_CARD_CAP_APDU_EXT;
	}
	//Added for virtual keycard:
	//sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: in muscle_init. (card->caps & SC_CARD_CAP_APDU_EXT): %i.\n", (card->caps & SC_CARD_CAP_APDU_EXT));
	/*if((card->caps & SC_CARD_CAP_APDU_EXT) != 0){
		card->caps ^= SC_CARD_CAP_APDU_EXT;
	}
	sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: after setting it to 0. (card->caps & SC_CARD_CAP_APDU_EXT): %i.\n", (card->caps & SC_CARD_CAP_APDU_EXT));
	//End of added for virtual keycard*/


	/* FIXME: Card type detection */
	if (1) {
		unsigned long flags;
		
		flags = SC_ALGORITHM_RSA_RAW;
		flags |= SC_ALGORITHM_RSA_HASH_NONE;
		flags |= SC_ALGORITHM_ONBOARD_KEY_GEN;

		_sc_card_add_rsa_alg(card, 1024, flags, 0);
		_sc_card_add_rsa_alg(card, 2048, flags, 0);
	}
	
	//Added for Virtual Keycard
	MUSCLE_DATA(card)->pace_established = 0;
	//End of added for Virtual Keycard
	
	return SC_SUCCESS;
}

static int muscle_list_files(sc_card_t *card, u8 *buf, size_t bufLen)
{
	muscle_private_t* priv = MUSCLE_DATA(card);
	mscfs_t *fs = priv->fs;
	int x;
	int count = 0;

	mscfs_check_cache(priv->fs);
	
	for(x = 0; x < fs->cache.size; x++) {
		u8* oid= fs->cache.array[x].objectId.id;
		sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL,
			"FILE: %02X%02X%02X%02X\n",
			oid[0],oid[1],oid[2],oid[3]);
		if(0 == memcmp(fs->currentPath, oid, 2)) {
			buf[0] = oid[2];
			buf[1] = oid[3];
			if(buf[0] == 0x00 && buf[1] == 0x00) continue; /* No directories/null names outside of root */
			buf += 2;
			count+=2;
		}
	}
	return count;
}

//EF Card Access taken from PACE worked example
const unsigned char my_ef_cardaccess[] = {0x31, 0x81, 0xc6, 0x30, 0x0d, 0x06, 0x08, 0x04, 0x00, 0x7f, 0x00, 0x07, 0x02, 0x02, 0x02, 0x02, 0x01, 0x02, 0x30, 0x12, 
	0x06, 0x0a, 0x04, 0x00, 0x7f, 0x00, 0x07, 0x02, 0x02, 0x03, 0x02, 0x02, 0x02, 0x01, 0x02, 0x02, 0x01, 0x01, 0x30, 0x12, 0x06, 0x0a, 0x04, 0x00, 0x7f, 0x00,
	0x07, 0x02, 0x02, 0x04, 0x02, 0x02, 0x02, 0x01, 0x02, 0x02, 0x01, 0x0D, 0x30, 0x1c, 0x06, 0x09, 0x04, 0x00, 0x7f, 0x00, 0x07, 0x02, 0x02, 0x03, 0x02, 0x30,
	0x0c, 0x06, 0x07, 0x04, 0x00, 0x7f, 0x00, 0x07, 0x01, 0x02, 0x02, 0x01, 0x0D, 0x02, 0x01, 0x01, 0x30, 0x2f, 0x06, 0x08, 0x04, 0x00, 0x7f, 0x00, 0x07, 0x02, 
	0x02, 0x06, 0x16, 0x23, 0x68, 0x74, 0x74, 0x70, 0x73, 0x3a, 0x2f, 0x2f, 0x77, 0x77, 0x77, 0x2e, 0x68, 0x6a, 0x70, 0x2d, 0x63, 0x6f, 0x6e, 0x73, 0x75, 0x6c,
	0x74, 0x69, 0x6e, 0x67, 0x2e, 0x63, 0x6f, 0x6d, 0x2f, 0x68, 0x6f, 0x6d, 0x65, 0x30, 0x3e, 0x06, 0x08, 0x04, 0x00, 0x7f, 0x00, 0x07, 0x02, 0x02, 0x08, 0x31,
	0x32, 0x30, 0x12, 0x06, 0x0a, 0x04, 0x00, 0x7f, 0x00, 0x07, 0x02, 0x02, 0x03, 0x02, 0x02, 0x02, 0x01, 0x02, 0x02, 0x01, 0x02, 0x30, 0x1c, 0x06, 0x09, 0x04,
	0x00, 0x7f, 0x00, 0x07, 0x02, 0x02, 0x03, 0x02, 0x30, 0x0c, 0x06, 0x07, 0x04, 0x00, 0x7f, 0x00, 0x07, 0x01, 0x02, 0x02, 0x01, 0x0D, 0x02, 0x01, 0x02};
 
//Code taken from https://github.com/frankmorgner/vsmartcard/blob/master/npa/src/card-npa.c
//Added for Virtual Keycard
void muscle_get_cache(struct sc_card *card,
        unsigned char pin_id, const unsigned char **pin, size_t *pin_length,
        unsigned char **ef_cardaccess, size_t *ef_cardaccess_length)
{
	*ef_cardaccess = my_ef_cardaccess;
	*ef_cardaccess_length = sizeof(my_ef_cardaccess);
}

int muscle_pin_cmd(sc_card_t *card, struct sc_pin_cmd_data *cmd,
				int *tries_left)
{
	muscle_private_t* priv = MUSCLE_DATA(card);
	const int bufferLength = MSC_MAX_PIN_COMMAND_LENGTH;
	u8 buffer[MSC_MAX_PIN_COMMAND_LENGTH];
	
	//Added for Virtual Keycard: Establish PACE channel
	int pace_failed = 1;
	if((cmd->cmd == SC_PIN_CMD_VERIFY || cmd->cmd == SC_PIN_CMD_CHANGE || cmd->cmd == SC_PIN_CMD_UNBLOCK)
		&& (MUSCLE_DATA(card)->pace_established != 1)
	){
		struct establish_pace_channel_input pace_input;
		struct establish_pace_channel_output pace_output;
		
		memset(&pace_input, 0, sizeof pace_input);
		memset(&pace_output, 0, sizeof pace_output);
		
		pace_input.pin_id = PACE_PIN_ID_PIN;
		
		//if (cmd->pin1) {
			pace_input.pin = cmd->pin1.data;
			pace_input.pin_length = cmd->pin1.len;
		//}
		
		muscle_get_cache(card, pace_input.pin_id, &pace_input.pin,
			&pace_input.pin_length, &pace_output.ef_cardaccess,
			&pace_output.ef_cardaccess_length);
		pace_failed = perform_pace(card, pace_input, &pace_output, EAC_TR_VERSION_2_02);
		//If PACE channel could not be established, do not try to login. That way, we do not reveal parts of pin if we make a typing mistake.
		if(pace_failed != 0)
			return -1;
		MUSCLE_DATA(card)->pace_established = 1;
	}
	//End of added for Virtual Keycard
	
	switch(cmd->cmd) {
	//Added for Virtual Keycard
	case SC_PIN_CMD_GET_INFO:
		switch (cmd->pin_reference) {
			case PACE_PIN_ID_CAN:
			case PACE_PIN_ID_MRZ:
			case PACE_PIN_ID_PUK:
				return SC_SUCCESS;
			case PACE_PIN_ID_PIN:
				return SC_SUCCESS;
			default:
				return SC_ERROR_OBJECT_NOT_FOUND;
		}
	//End of added for Virtual Keycard
		
	case SC_PIN_CMD_VERIFY:
		switch(cmd->pin_type) {
		case SC_AC_CHV: {
			sc_apdu_t apdu;
			int r;
			msc_verify_pin_apdu(card, &apdu, buffer, bufferLength, cmd->pin_reference, cmd->pin1.data, cmd->pin1.len);
			cmd->apdu = &apdu;
			cmd->pin1.offset = 5;
			r = iso_ops->pin_cmd(card, cmd, tries_left);
			if(r >= 0)
				priv->verifiedPins |= (1 << cmd->pin_reference);
			return r;
		}
		case SC_AC_TERM:
		case SC_AC_PRO:
		case SC_AC_AUT:
		case SC_AC_NONE:
		default:
			sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Unsupported authentication method\n");
			return SC_ERROR_NOT_SUPPORTED;
		}
	case SC_PIN_CMD_CHANGE:
		switch(cmd->pin_type) {
		case SC_AC_CHV: {
			sc_apdu_t apdu;
			msc_change_pin_apdu(card, &apdu, buffer, bufferLength, cmd->pin_reference, cmd->pin1.data, cmd->pin1.len, cmd->pin2.data, cmd->pin2.len);
			cmd->apdu = &apdu;
			return iso_ops->pin_cmd(card, cmd, tries_left);
		}
		case SC_AC_TERM:
		case SC_AC_PRO:
		case SC_AC_AUT:
		case SC_AC_NONE:
		default:
			sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Unsupported authentication method\n");
			return SC_ERROR_NOT_SUPPORTED;
		}
	case SC_PIN_CMD_UNBLOCK:
	switch(cmd->pin_type) {
		case SC_AC_CHV: {
			sc_apdu_t apdu;
			msc_unblock_pin_apdu(card, &apdu, buffer, bufferLength, cmd->pin_reference, cmd->pin1.data, cmd->pin1.len);
			cmd->apdu = &apdu;
			return iso_ops->pin_cmd(card, cmd, tries_left);
		}
		case SC_AC_TERM:
		case SC_AC_PRO:
		case SC_AC_AUT:
		case SC_AC_NONE:
		default:
			sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Unsupported authentication method\n");
			return SC_ERROR_NOT_SUPPORTED;
		}
	default:
		sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Unsupported command\n");
		return SC_ERROR_NOT_SUPPORTED;

	}
	
}

static int muscle_card_extract_key(sc_card_t *card, sc_cardctl_muscle_key_info_t *info)
{
	/* CURRENTLY DONT SUPPOT EXTRACTING PRIVATE KEYS... */
	switch(info->keyType) {
	case 1: /* RSA */
		return msc_extract_rsa_public_key(card,
			info->keyLocation,
			&info->modLength,
			&info->modValue,
			&info->expLength,
			&info->expValue);
	default:
		return SC_ERROR_NOT_SUPPORTED;
	}
}

static int muscle_card_import_key(sc_card_t *card, sc_cardctl_muscle_key_info_t *info)
{
	/* CURRENTLY DONT SUPPOT EXTRACTING PRIVATE KEYS... */
	switch(info->keyType) {
	case 0x02: /* RSA_PRIVATE */
	case 0x03: /* RSA_PRIVATE_CRT */
		return msc_import_key(card,
			info->keyLocation,
			info);
	default:
		return SC_ERROR_NOT_SUPPORTED;
	}
}

static int muscle_card_generate_key(sc_card_t *card, sc_cardctl_muscle_gen_key_info_t *info)
{
	return msc_generate_keypair(card,
		info->privateKeyLocation,
		info->publicKeyLocation,
		info->keyType,
		info->keySize,
		0);
}

static int muscle_card_verified_pins(sc_card_t *card, sc_cardctl_muscle_verified_pins_info_t *info)
{
	muscle_private_t* priv = MUSCLE_DATA(card);
	info->verifiedPins = priv->verifiedPins;
	return 0;
}
static int muscle_card_ctl(sc_card_t *card, unsigned long request, void *data)
{
	switch(request) {
	case SC_CARDCTL_MUSCLE_GENERATE_KEY:
		return muscle_card_generate_key(card, (sc_cardctl_muscle_gen_key_info_t*) data);
	case SC_CARDCTL_MUSCLE_EXTRACT_KEY:
		return muscle_card_extract_key(card, (sc_cardctl_muscle_key_info_t*) data);
	case SC_CARDCTL_MUSCLE_IMPORT_KEY:
		return muscle_card_import_key(card, (sc_cardctl_muscle_key_info_t*) data);
	case SC_CARDCTL_MUSCLE_VERIFIED_PINS:
		return muscle_card_verified_pins(card, (sc_cardctl_muscle_verified_pins_info_t*) data);
	default:
		return SC_ERROR_NOT_SUPPORTED; /* Unsupported.. whatever it is */
	}
}

static int muscle_set_security_env(sc_card_t *card,
				 const sc_security_env_t *env,
				 int se_num)
{
	muscle_private_t* priv = MUSCLE_DATA(card);

	if (env->operation != SC_SEC_OPERATION_SIGN &&
	    env->operation != SC_SEC_OPERATION_DECIPHER) {
		sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Invalid crypto operation supplied.\n");
		return SC_ERROR_NOT_SUPPORTED;
	}
	if (env->algorithm != SC_ALGORITHM_RSA) {
		sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Invalid crypto algorithm supplied.\n");
		return SC_ERROR_NOT_SUPPORTED;
	}
	/* ADJUST FOR PKCS1 padding support for decryption only */
	if ((env->algorithm_flags & SC_ALGORITHM_RSA_PADS) ||
	    (env->algorithm_flags & SC_ALGORITHM_RSA_HASHES)) {
	    	sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Card supports only raw RSA.\n");
		return SC_ERROR_NOT_SUPPORTED;
	}
	if (env->flags & SC_SEC_ENV_KEY_REF_PRESENT) {
		if (env->key_ref_len != 1 ||
		    (env->key_ref[0] > 0x0F)) {
			sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Invalid key reference supplied.\n");
			return SC_ERROR_NOT_SUPPORTED;
		}
		priv->rsa_key_ref = env->key_ref[0];
	}
	if (env->flags & SC_SEC_ENV_ALG_REF_PRESENT) {
		sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Algorithm reference not supported.\n");
		return SC_ERROR_NOT_SUPPORTED;
	}
	/* if (env->flags & SC_SEC_ENV_FILE_REF_PRESENT)
		if (memcmp(env->file_ref.value, "\x00\x12", 2) != 0) {
			sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "File reference is not 0012.\n");
			return SC_ERROR_NOT_SUPPORTED;
		} */
	priv->env = *env;
	return 0;
}

static int muscle_restore_security_env(sc_card_t *card, int se_num)
{
	muscle_private_t* priv = MUSCLE_DATA(card);
	memset(&priv->env, 0, sizeof(priv->env));
	return 0;
}

//Added for virtual keycard
static msc_id inputId = { { 0xFF, 0xFF, 0xFF, 0xFF } };
static msc_id outputId = { { 0xFF, 0xFF, 0xFF, 0xFE } };
/* Stream data to the card through file IO */
static int virtual_keycard_compute_crypt_final_object(
			sc_card_t *card, 
			int keyLocation,
			const u8* inputData,
			u8* outputData,
			size_t dataLength,
			size_t* outputDataLength,
			int decrypt)
{
	sc_apdu_t apdu;
	u8 buffer[MSC_MAX_APDU];
	u8 *ptr;
	int r;

	sc_format_apdu(card, &apdu, SC_APDU_CASE_3_SHORT, 0x36, keyLocation, 0x03); /* Final */
	
	apdu.data = buffer;
	apdu.datalen = 1;
	apdu.lc = 1;
	
	ptr = buffer;
	*ptr = 0x02;
	ptr++; /* DATA LOCATION: OBJECT */
	*ptr = (dataLength >> 8) & 0xFF;
	ptr++;
	*ptr = dataLength & 0xFF;
	ptr++;
	memcpy(ptr, inputData, dataLength);

	r = msc_create_object(card, outputId, dataLength + 2, 0x02, 0x02, 0x02);
	if(r < 0) { 
		if(r == SC_ERROR_FILE_ALREADY_EXISTS) {
			r = msc_delete_object(card, outputId, 0);
			if(r < 0) {
				SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_VERBOSE, r);
			}
			r = msc_create_object(card, outputId, dataLength + 2, 0x02, 0x02, 0x02);
			if(r < 0) {
				SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_VERBOSE, r);
			}
		}
	}

	r = msc_update_object(card, outputId, 0, buffer + 1, dataLength + 2);
	if(r < 0) return r; 
	
	r = sc_transmit_apdu(card, &apdu);
	SC_TEST_RET(card->ctx, SC_LOG_DEBUG_NORMAL, r, "APDU transmit failed");
	if(apdu.sw1 == 0x90 && apdu.sw2 == 0x00) {
		//sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: in virtual_keycard_compute_crypt_final_object. will call msc_read_object. dataLength: %i\n", dataLength);
		if(decrypt)
			dataLength--;
		r = msc_read_object(card, inputId, 2, outputData, dataLength);
		*outputDataLength = dataLength;
		msc_delete_object(card, outputId, 0);
		msc_delete_object(card, inputId, 0);
		return 0;
	}
	r = sc_check_sw(card, apdu.sw1, apdu.sw2);
	if (r) {
		if (card->ctx->debug >= 2) {
			sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "final: got strange SWs: 0x%02X 0x%02X\n",
			     apdu.sw1, apdu.sw2);
		}
	} else {
		r = SC_ERROR_CARD_CMD_FAILED;
	}
	/* this is last ditch cleanup */	
	msc_delete_object(card, outputId, 0);
	
	SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, r);
}

int virtual_keycard_compute_crypt(sc_card_t *card, 
			int keyLocation,
			int cipherMode,
			int cipherDirection,
			const u8* data,
			u8* outputData,
			size_t dataLength,
			size_t outputDataLength)
{
	size_t left = dataLength;
	const u8* inPtr = data;
	u8* outPtr = outputData;
	int toSend;
	int r;
	int decrypt;
	
	size_t received = 0;
	assert(outputDataLength >= dataLength);
	
	//sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: in virtual_keycard_compute_crypt. dataLength: %i\n", dataLength);
	
	/* Don't send data during init... apparently current version does not support it */
	toSend = 0;
	r = msc_compute_crypt_init(card, 
		keyLocation, 
		cipherMode, 
		cipherDirection, 
		inPtr, 
		outPtr, 
		toSend,
		&received);
	if(r < 0) SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, r);
	left -= toSend;
	inPtr += toSend;
	outPtr += received;

	toSend = MIN(left, MSC_MAX_APDU - 5);
	/* If the card supports extended APDUs, or the data fits in
           one normal APDU, use it for the data exchange */
	//sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: left: %i, MSC_MAX_SEND: %i.\n", left, MSC_MAX_SEND);
	/*if (left < (MSC_MAX_SEND - 4) || (card->caps & SC_CARD_CAP_APDU_EXT) != 0) {
		sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: data shall fit in one normal apdu or extended apdus enabled.\n");
		r = msc_compute_crypt_final(card,
			keyLocation,
			inPtr,
			outPtr,
			toSend,
			&received);
		if(r < 0) SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, r);
	} else {*/ /* Data is too big: use objects */
		//sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: data was too big, using objects now.\n");
	if(cipherDirection == 0x04)
		decrypt = 1;
	else
		decrypt = 0;
	r = virtual_keycard_compute_crypt_final_object(card,
		keyLocation,
		inPtr,
		outPtr,
		toSend,
		&received,
		decrypt);
	if(r < 0) SC_FUNC_RETURN(card->ctx, SC_LOG_DEBUG_NORMAL, r);
	//}	
	outPtr += received;

	return outPtr - outputData; /* Amt received */
}
//End of added for virtual keycard

static int muscle_decipher(sc_card_t * card,
			 const u8 * crgram, size_t crgram_len, u8 * out,
			 size_t out_len)
{
	muscle_private_t* priv = MUSCLE_DATA(card);
	
	u8 key_id;
	int r;

	//sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: muscle_decipher called!");
	
	/* saniti check */
	if (priv->env.operation != SC_SEC_OPERATION_DECIPHER)
		return SC_ERROR_INVALID_ARGUMENTS;
	
	key_id = priv->rsa_key_ref * 2; /* Private key */

	if (out_len < crgram_len) {
		sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Output buffer too small");
		return SC_ERROR_BUFFER_TOO_SMALL;
	}
	
	r = virtual_keycard_compute_crypt(card,
		key_id,
		0x00, /* RSA NO PADDING */
		0x04, /* decrypt */
		crgram,
		out,
		crgram_len,
		out_len);
	SC_TEST_RET(card->ctx, SC_LOG_DEBUG_NORMAL, r, "Card signature failed");
	return r;
}

static int muscle_compute_signature(sc_card_t *card, const u8 *data,
		size_t data_len, u8 * out, size_t outlen)
{
	muscle_private_t* priv = MUSCLE_DATA(card);
	u8 key_id;
	int r;
	
	//sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: muscle_compute_signature called.\n");
	//sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: (card->caps & SC_CARD_CAP_APDU_EXT): %i.\n", (card->caps & SC_CARD_CAP_APDU_EXT));
	/*if((card->caps & SC_CARD_CAP_APDU_EXT) != 0){
		card->caps ^= SC_CARD_CAP_APDU_EXT;
	}
	sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "eriks debug: after setting it to 0. (card->caps & SC_CARD_CAP_APDU_EXT): %i.\n", (card->caps & SC_CARD_CAP_APDU_EXT));
	*/
	key_id = priv->rsa_key_ref * 2; /* Private key */
	
	if (outlen < data_len) {
		sc_debug(card->ctx, SC_LOG_DEBUG_NORMAL, "Output buffer too small");
		return SC_ERROR_BUFFER_TOO_SMALL;
	}
	
	r = virtual_keycard_compute_crypt(card,
		key_id,
		0x00, /* RSA NO PADDING */
		0x01, /* -- decrypt raw... will do what we need since signing isn't yet supported */ //Changed for Virtual Keycard. Works with virtual keycard
		data,
		out,
		data_len,
		outlen);
	
	SC_TEST_RET(card->ctx, SC_LOG_DEBUG_NORMAL, r, "Card signature failed");
	return r;
}

static int muscle_get_challenge(sc_card_t *card, u8 *rnd, size_t len)
{
	if (len == 0)
		return SC_SUCCESS;
	else
		return msc_get_challenge(card, len, 0, NULL, rnd);
}

static int muscle_check_sw(sc_card_t * card, unsigned int sw1, unsigned int sw2) {
	if(sw1 == 0x9C) {
		switch(sw2) {
			case 0x01: /* SW_NO_MEMORY_LEFT */
				return SC_ERROR_NOT_ENOUGH_MEMORY;
			case 0x02: /* SW_AUTH_FAILED */
				return SC_ERROR_PIN_CODE_INCORRECT;
			case 0x03: /* SW_OPERATION_NOT_ALLOWED */
				return SC_ERROR_NOT_ALLOWED;
			case 0x05: /* SW_UNSUPPORTED_FEATURE */
				return SC_ERROR_NO_CARD_SUPPORT;
			case 0x06: /* SW_UNAUTHORIZED */
				return SC_ERROR_SECURITY_STATUS_NOT_SATISFIED;
			case 0x07: /* SW_OBJECT_NOT_FOUND */
				return SC_ERROR_FILE_NOT_FOUND;
			case 0x08: /* SW_OBJECT_EXISTS */
				return SC_ERROR_FILE_ALREADY_EXISTS;
			case 0x09: /* SW_INCORRECT_ALG */
				return SC_ERROR_INCORRECT_PARAMETERS;
			case 0x0B: /* SW_SIGNATURE_INVALID */
				return SC_ERROR_CARD_CMD_FAILED;
			case 0x0C: /* SW_IDENTITY_BLOCKED */
				return SC_ERROR_AUTH_METHOD_BLOCKED;
			case 0x0F: /* SW_INVALID_PARAMETER */
			case 0x10: /* SW_INCORRECT_P1 */
			case 0x11: /* SW_INCORRECT_P2 */
				return SC_ERROR_INCORRECT_PARAMETERS;
		}
	}
	return iso_ops->check_sw(card, sw1, sw2);
}


static struct sc_card_driver * sc_get_driver(void)
{
	struct sc_card_driver *iso_drv = sc_get_iso7816_driver();
	if (iso_ops == NULL)
		iso_ops = iso_drv->ops;

	muscle_ops = *iso_drv->ops;
	
	muscle_ops.check_sw = muscle_check_sw;
	muscle_ops.pin_cmd = muscle_pin_cmd;
	
	muscle_ops.match_card = muscle_match_card;
	muscle_ops.init = muscle_init;
	muscle_ops.finish = muscle_finish;
	
	muscle_ops.get_challenge = muscle_get_challenge;
	
	muscle_ops.set_security_env = muscle_set_security_env;
	muscle_ops.restore_security_env = muscle_restore_security_env;
	muscle_ops.compute_signature = muscle_compute_signature;
	muscle_ops.decipher = muscle_decipher;
	muscle_ops.card_ctl = muscle_card_ctl;
	muscle_ops.read_binary = muscle_read_binary;
	muscle_ops.update_binary = muscle_update_binary;
	muscle_ops.create_file = muscle_create_file;
	muscle_ops.select_file = muscle_select_file;
	muscle_ops.delete_file = muscle_delete_file;
	muscle_ops.list_files = muscle_list_files;
	

	return &muscle_drv;
}

struct sc_card_driver * sc_get_muscle_driver(void)
{
	return sc_get_driver();
}

//Added for Virtual Keycard. Code taken from libnpa.
void *sc_module_init(const char *name)
{
    const char virtualkeycard_name[] = "virtualkeycard";
    if (name) {
        if (strcmp(virtualkeycard_name, name) == 0)
            return sc_get_muscle_driver;
    }
    return NULL;
}

const char *sc_driver_version(void)
{
    /* Tested with OpenSC 0.12 and 0.13.0, which can't be captured by checking
     * our version info against OpenSC's PACKAGE_VERSION. For this reason we
     * tell OpenSC that everything is fine, here. */
    return sc_get_version();
}
//End of added for Virtual Keycard.